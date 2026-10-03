package com.sparkora.kb.service;

import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KB 写入事务边界单测（09-27 R3/AC6；10-03 E6 旧表退役）。
 * 断言：rebuild 串行，embed 在事务外，持久化经自注入代理（self）走独立事务方法 {@code persistChunk}；
 * 向量只写单表 store（不再写旧表）。
 */
class KbDocTransactionTest {

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

    private static void setSelf(KbDocService target, KbDocService proxy) {
        try {
            Field f = KbDocService.class.getDeclaredField("self");
            f.setAccessible(true);
            f.set(target, proxy);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStore(KbDocService target, VectorStoreService store) {
        try {
            Field f = KbDocService.class.getDeclaredField("vectorStoreService");
            f.setAccessible(true);
            f.set(target, store);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rebuild_经自注入代理_串行持久化() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        KbDocService service = new KbDocService(docMapper, chunkMapper, client,
                new EmbeddingBatchRunner(client), new ObjectMapper());
        KbDocService spySelf = spy(service);
        setSelf(service, spySelf);

        KbDocEntity d = new KbDocEntity();
        d.setId(5L);
        d.setTitle("充电桩");
        d.setDomain("充电");
        d.setContent("第一段。\n\n第二段。");
        d.setEnabled(true);
        when(docMapper.selectById(5L)).thenReturn(d);

        service.rebuild(5L);

        // 两块 → 代理方法各调一次（独立事务边界）
        verify(spySelf, org.mockito.Mockito.times(2)).persistChunk(any(KbChunkEntity.class), eq("[0.1,0.2]"));
    }

    @Test
    void persistChunk_先插chunk后写store向量() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        KbDocService service = new KbDocService(docMapper, chunkMapper, client,
                new EmbeddingBatchRunner(client), new ObjectMapper());
        setStore(service, store);

        KbChunkEntity c = new KbChunkEntity();
        c.setDocId(5L);
        c.setSeq(0);
        c.setChunkText("知识：充电桩（充电）\n正文。");
        c.setDocTitle("充电桩");
        doAnswer(inv -> { ((KbChunkEntity) inv.getArgument(0)).setId(91L); return 1; })
                .when(chunkMapper).insert(any(KbChunkEntity.class));

        service.persistChunk(c, "[0.1,0.2]");

        var inOrder = inOrder(chunkMapper, store);
        inOrder.verify(chunkMapper).insert(c);
        inOrder.verify(store).upsert(eq("KB"), eq(91L), eq(null), eq("KB_CHUNK"), anyString(),
                anyBoolean(), any(), anyString(), eq("[0.1,0.2]"), any());
    }

    @Test
    void 停用文档_清块后零向量化() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = mock(FakeEmbeddingClient.class);
        KbDocService service = new KbDocService(docMapper, chunkMapper, client,
                new EmbeddingBatchRunner(client), new ObjectMapper());
        setStore(service, store);

        KbDocEntity d = new KbDocEntity();
        d.setId(5L);
        d.setEnabled(false);
        when(docMapper.selectById(5L)).thenReturn(d);

        var st = service.rebuild(5L);

        org.junit.jupiter.api.Assertions.assertEquals(0, st.total());
        verify(client, org.mockito.Mockito.never()).embed(anyString());
        verify(store, org.mockito.Mockito.never()).upsert(anyString(), any(), any(), anyString(),
                anyString(), anyBoolean(), any(), anyString(), anyString(), any());
    }
}
