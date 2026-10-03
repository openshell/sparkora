package com.sparkora.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.entity.KbChunkEntity;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbChunkMapper;
import com.sparkora.mapper.KbDocMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-03 E3 KbDocService 新维度单测（纯 Mockito，不连库）：
 * 标签 normalize、生效期 active 判定、rebuild 写 store metadata 扩展键。
 */
class KbDocE3Test {

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

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = KbDocService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KbDocService service(KbDocMapper docMapper, KbChunkMapper chunkMapper,
                                        EmbeddingClient client) {
        return new KbDocService(docMapper, chunkMapper, client,
                new EmbeddingBatchRunner(client), new ObjectMapper());
    }

    @Test
    void normalizeTags_trim去空去重保序_超长拒绝() {
        assertEquals(List.of("a", "b"), KbDocService.normalizeTags(
                java.util.Arrays.asList(" a ", "", "b", "a", null)));
        assertEquals(List.of(), KbDocService.normalizeTags(null));
        String longTag = "字".repeat(51);
        assertThrows(IllegalArgumentException.class, () -> KbDocService.normalizeTags(List.of(longTag)));
    }

    @Test
    void isActive_生效期边界含端点_null不限() {
        LocalDate today = LocalDate.of(2026, 10, 3);
        assertTrue(KbDocService.isActive(true, null, null, today));
        assertTrue(KbDocService.isActive(true, today, today, today));
        assertFalse(KbDocService.isActive(true, today.plusDays(1), null, today));   // 未来生效
        assertFalse(KbDocService.isActive(true, null, today.minusDays(1), today));  // 已过期
        assertFalse(KbDocService.isActive(false, null, null, today));               // 停用
        assertFalse(KbDocService.isActive(null, null, null, today));                // enabled 未置
    }

    @Test
    void rebuild_写store元数据含生效期来源标签_并按生效期计算active() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        VectorStoreService store = mock(VectorStoreService.class);
        KbDocService service = service(docMapper, chunkMapper, client);
        setField(service, "vectorStoreService", store);

        KbDocEntity d = new KbDocEntity();
        d.setId(9L);
        d.setTitle("充电桩");
        d.setDomain("充电");
        d.setSource("https://example.com/a");
        d.setEffectiveFrom(LocalDate.now().plusDays(1));   // 未来生效 → active=false
        d.setEffectiveTo(null);
        d.setContent("正文。");
        d.setEnabled(true);
        when(docMapper.selectById(9L)).thenReturn(d);
        // chunk 插入回填 id
        org.mockito.Mockito.doAnswer(inv -> {
            ((KbChunkEntity) inv.getArgument(0)).setId(77L);
            return 1;
        }).when(chunkMapper).insert(any(KbChunkEntity.class));

        service.rebuild(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(store).upsert(eq("KB"), eq(77L), eq(null), eq("KB_CHUNK"), eq("充电桩"),
                eq(false), eq("test-embed"), anyString(), eq("[0.1,0.2]"), meta.capture());
        assertEquals("https://example.com/a", meta.getValue().get("source"));
        assertEquals(d.getEffectiveFrom().toString(), meta.getValue().get("effectiveFrom"));
        assertFalse(meta.getValue().containsKey("effectiveTo"));
        assertFalse(meta.getValue().containsKey("tags"));
    }

    @Test
    void rebuild_启用期命中_active为true且标签透传() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        VectorStoreService store = mock(VectorStoreService.class);
        com.sparkora.mapper.KbDocTagMapper tagMapper = mock(com.sparkora.mapper.KbDocTagMapper.class);
        KbDocService service = service(docMapper, chunkMapper, client);
        setField(service, "vectorStoreService", store);
        setField(service, "tagMapper", tagMapper);

        KbDocEntity d = new KbDocEntity();
        d.setId(10L);
        d.setTitle("保养");
        d.setDomain("保养");
        d.setContent("正文。");
        d.setEnabled(true);
        when(docMapper.selectById(10L)).thenReturn(d);
        when(tagMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(inv -> {
            ((KbChunkEntity) inv.getArgument(0)).setId(88L);
            return 1;
        }).when(chunkMapper).insert(any(KbChunkEntity.class));

        service.rebuild(10L);

        verify(store).upsert(eq("KB"), eq(88L), eq(null), eq("KB_CHUNK"), eq("保养"),
                eq(true), eq("test-embed"), anyString(), eq("[0.1,0.2]"), any());
    }

    @Test
    void replaceTags_全量覆盖_空列表清空() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        com.sparkora.mapper.KbDocTagMapper tagMapper = mock(com.sparkora.mapper.KbDocTagMapper.class);
        KbDocService service = service(docMapper, chunkMapper, client);
        setField(service, "tagMapper", tagMapper);

        service.replaceTags(3L, List.of("a", "b", "a"), "alice");
        verify(tagMapper).deleteByDocId(3L);
        verify(tagMapper, org.mockito.Mockito.times(2)).insert(any(com.sparkora.domain.entity.KbDocTagEntity.class));

        service.replaceTags(3L, List.of(), "alice");
        verify(tagMapper, org.mockito.Mockito.times(2)).deleteByDocId(3L);
    }

    @Test
    void rebuild_停用_不写store并零向量化() {
        KbDocMapper docMapper = mock(KbDocMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        FakeEmbeddingClient client = mock(FakeEmbeddingClient.class);
        VectorStoreService store = mock(VectorStoreService.class);
        KbDocService service = service(docMapper, chunkMapper, client);
        setField(service, "vectorStoreService", store);

        KbDocEntity d = new KbDocEntity();
        d.setId(5L);
        d.setEnabled(false);
        when(docMapper.selectById(5L)).thenReturn(d);

        var st = service.rebuild(5L);
        assertEquals(0, st.total());
        verify(store, org.mockito.Mockito.never()).upsert(anyString(), anyLong(), any(), anyString(),
                anyString(), anyBoolean(), anyString(), anyString(), anyString(), any());
    }
}
