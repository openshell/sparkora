package com.sparkora.news.service;

import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NEWS 写入事务边界单测（09-27 R3/AC6；10-03 E6 旧表退役）。
 *
 * 断言：embed 在事务外、持久化经自注入代理（self）走独立事务方法 {@code persistNewsDoc}；
 * 向量只写单表 store（不再写旧表）；修复此前 {@code @Transactional insertDocWithEmbedding} 同类直调失效。
 */
class NewsDocTransactionTest {

    /** 固定返回合法向量的 embedding 假件。 */
    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() {
            super(props());
        }
        private static AiProperties props() {
            AiProperties p = new AiProperties();
            p.setEmbeddingModel("test-embed");
            return p;
        }
        @Override public String embed(String text) { return "[0.1,0.2]"; }
    }

    private static void setSelf(NewsDocService target, NewsDocService proxy) {
        try {
            Field f = NewsDocService.class.getDeclaredField("self");
            f.setAccessible(true);
            f.set(target, proxy);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStore(NewsDocService target, VectorStoreService store) {
        try {
            Field f = NewsDocService.class.getDeclaredField("vectorStoreService");
            f.setAccessible(true);
            f.set(target, store);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rebuild_经自注入代理走独立事务持久化() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);
        NewsDocService service = new NewsDocService(newsMapper, docMapper, client, runner);
        NewsDocService spySelf = spy(service);
        setSelf(service, spySelf);

        NewsEntity n = new NewsEntity();
        n.setId(9L);
        n.setTitle("标题");
        n.setPublishDate(LocalDateTime.of(2026, 9, 1, 0, 0));
        n.setContent("正文。");
        when(newsMapper.selectById(9L)).thenReturn(n);

        service.rebuildForNews(9L);

        // 持久化经代理方法（独立事务边界），而非匿名 lambda 内直调
        verify(spySelf).persistNewsDoc(any(NewsDocEntity.class), eq("[0.1,0.2]"));
    }

    @Test
    void persistNewsDoc_先插doc后写store向量() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        NewsDocService service = new NewsDocService(newsMapper, docMapper, client,
                new EmbeddingBatchRunner(client));
        setStore(service, store);

        NewsDocEntity doc = new NewsDocEntity();
        doc.setNewsId(9L);
        doc.setSeq(0);
        doc.setChunkText("新闻：标题\n正文。");
        doc.setNewsTitle("标题");
        // 模拟 MyBatis-Plus 回填自增 id
        doAnswer(inv -> { ((NewsDocEntity) inv.getArgument(0)).setId(123L); return 1; })
                .when(docMapper).insert(any(NewsDocEntity.class));

        service.persistNewsDoc(doc, "[0.1,0.2]");

        var inOrder = inOrder(docMapper, store);
        inOrder.verify(docMapper).insert(doc);
        inOrder.verify(store).upsert(eq("NEWS"), eq(123L), eq(null), eq(null),
                eq("标题"), eq(true), eq("test-embed"), anyString(), eq("[0.1,0.2]"));
    }

    @Test
    void rebuild_无正文无标题_不调embedding不持久化() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = mock(FakeEmbeddingClient.class);
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);
        NewsDocService service = new NewsDocService(newsMapper, docMapper, client, runner);
        setStore(service, store);

        NewsEntity n = new NewsEntity();
        n.setId(9L);
        n.setContent(null);
        n.setTitle(null);
        when(newsMapper.selectById(9L)).thenReturn(n);

        service.rebuildForNews(9L);

        verify(client, never()).embed(anyString());
        verify(store, never()).upsert(anyString(), any(), any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyString(), anyString());
    }
}
