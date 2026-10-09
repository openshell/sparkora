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
        // 10-05 E:BYD 路径必须写 sourceType=byd-news / category=官方新闻 扩展 metadata(AC-E3)
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> meta =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        inOrder.verify(store).upsert(eq("NEWS"), eq(123L), eq(null), eq(null),
                eq("标题"), eq(true), eq("test-embed"), anyString(), eq("[0.1,0.2]"), meta.capture());
        org.junit.jupiter.api.Assertions.assertEquals("byd-news", meta.getValue().get("sourceType"));
        org.junit.jupiter.api.Assertions.assertEquals("官方新闻", meta.getValue().get("category"));
        // 10-09 M:BYD 路径必须写 authorityTier=official(供 F-R4 分档),url 非空时写入(供 F-R3 去重)
        org.junit.jupiter.api.Assertions.assertEquals("official", meta.getValue().get("authorityTier"));
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
                org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    // ==================== 10-09 M：BYD url 绝对化 + Spring 装配 ====================

    /** BYD 相对 URL 按 NewsProperties.detail_base_url 补全；已绝对原样；基址缺失返回原值（降级不去重）。 */
    @Test
    void absoluteBydUrl_按详情基址补全() {
        com.sparkora.config.NewsProperties props = new com.sparkora.config.NewsProperties();
        props.setDetailBaseUrl("https://www.byd.com/");
        NewsDocService svc = new NewsDocService(null, null, null, null, props);
        org.junit.jupiter.api.Assertions.assertEquals("https://www.byd.com/cn/detail634",
                svc.absoluteBydUrl("/cn/detail634"));
        org.junit.jupiter.api.Assertions.assertEquals("https://www.byd.com/cn/detail634",
                svc.absoluteBydUrl("cn/detail634"));
        org.junit.jupiter.api.Assertions.assertEquals("https://news.example/a", svc.absoluteBydUrl("https://news.example/a"));

        // newsProps=null（旧测试直 new 4 参）→ 原值返回，不 NPE
        NewsDocService legacy = new NewsDocService(null, null, null, null);
        org.junit.jupiter.api.Assertions.assertEquals("/cn/detail634", legacy.absoluteBydUrl("/cn/detail634"));
        org.junit.jupiter.api.Assertions.assertNull(legacy.absoluteBydUrl(null));
    }

    /**
     * Spring 装配回归（10-09 M）：NewsDocService 新增生产构造器（5 参，注入 NewsProperties）后，
     * 多构造器 {@code @Service} 须显式 {@code @Autowired}，否则应用启动失败但 mvn test 全绿
     * （先例 TavilySearchToolWiringTest / FactSheetServiceTest）。
     */
    @Test
    void NewsDocService可被Spring装配_多构造器须显式Autowired() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(NewsMapper.class, () -> mock(NewsMapper.class));
            ctx.registerBean(NewsDocMapper.class, () -> mock(NewsDocMapper.class));
            ctx.registerBean(EmbeddingClient.class, () -> new FakeEmbeddingClient());
            ctx.registerBean(com.sparkora.config.NewsProperties.class);
            ctx.registerBean(EmbeddingBatchRunner.class);
            ctx.register(NewsDocService.class);
            ctx.refresh();
            org.junit.jupiter.api.Assertions.assertNotNull(ctx.getBean(NewsDocService.class),
                    "NewsDocService 应可装配");
        }
    }
}
