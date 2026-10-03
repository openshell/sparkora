package com.sparkora.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-03 E1 {@link VectorStoreService} 单测（Mockito，不连库）。
 * 覆盖：确定性 uuid、upsert SQL/metadata、setActive/deleteByRef 直更 SQL、
 * searchDomains 过滤表达式构建与委托、searchImages refId 白名单。
 */
class VectorStoreServiceTest {

    @SuppressWarnings("unchecked")
    private static VectorStore mockStore(JdbcTemplate jt) {
        VectorStore vs = mock(VectorStore.class);
        when(vs.getNativeClient()).thenReturn(Optional.of(jt));
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        return vs;
    }

    @Test
    void docId_确定性_uuid_同引用恒定() {
        UUID a = VectorStoreService.docId("CAR", 42L);
        UUID b = VectorStoreService.docId("CAR", 42L);
        UUID c = VectorStoreService.docId("KB", 42L);
        assertEquals(a, b);
        assertTrue(!a.equals(c), "不同域同 refId 必须不同 id");
    }

    @Test
    void upsert_直写向量字面量_metadata含域字段() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        svc.upsert("CAR", 7L, 39L, "PARAM_GROUP", "海狮08EV", true, "m1", "车型：x", "[0.1,0.2]");

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object> meta = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jt).update(sql.capture(), eq(VectorStoreService.docId("CAR", 7L).toString()),
                eq("车型：x"), meta.capture(), eq("[0.1,0.2]"));
        assertTrue(sql.getValue().contains("?::uuid"), sql.getValue());
        assertTrue(sql.getValue().contains("?::jsonb"), sql.getValue());
        assertTrue(sql.getValue().contains("ON CONFLICT (id) DO UPDATE"), sql.getValue());
        String json = (String) meta.getValue();
        assertTrue(json.contains("\"domain\":\"CAR\""), json);
        assertTrue(json.contains("\"refId\":7"), json);
        assertTrue(json.contains("\"modelId\":39"), json);
        assertTrue(json.contains("\"active\":true"), json);
        assertTrue(json.contains("\"embeddingModel\":\"m1\""), json);
    }

    @Test
    void upsert_modelId为空_不写modelId键() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        svc.upsert("KB", 3L, null, "KB_CHUNK", "标题", true, "m1", "知识：x", "[0.1]");

        org.mockito.ArgumentCaptor<Object> meta = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jt).update(anyString(), any(), any(), meta.capture(), any());
        assertTrue(!((String) meta.getValue()).contains("modelId"), "非 CAR 域不得含 modelId");
    }

    @Test
    void upsert_扩展metadata_写入生效期来源标签() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("source", "https://example.com/a");
        extra.put("effectiveFrom", "2026-10-03");
        extra.put("effectiveTo", null);              // null 键跳过
        extra.put("tags", List.of("充电", "安全"));
        svc.upsert("KB", 3L, null, "KB_CHUNK", "标题", false, "m1", "知识：x", "[0.1]", extra);

        org.mockito.ArgumentCaptor<Object> meta = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jt).update(anyString(), any(), any(), meta.capture(), any());
        String json = (String) meta.getValue();
        assertTrue(json.contains("\"source\":\"https://example.com/a\""), json);
        assertTrue(json.contains("\"effectiveFrom\":\"2026-10-03\""), json);
        assertTrue(json.contains("\"tags\":[\"充电\",\"安全\"]"), json);
        assertTrue(json.contains("\"active\":false"), json);
        assertTrue(!json.contains("effectiveTo"), "null 扩展键不得写入");
    }

    @Test
    void upsert_旧9参重载_行为不变无扩展键() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        svc.upsert("CAR", 7L, 39L, "PARAM_GROUP", "海狮08EV", true, "m1", "车型：x", "[0.1]");
        org.mockito.ArgumentCaptor<Object> meta = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jt).update(anyString(), any(), any(), meta.capture(), any());
        String json = (String) meta.getValue();
        assertTrue(!json.contains("source"), json);
        assertTrue(!json.contains("effectiveFrom"), json);
        assertTrue(!json.contains("tags"), json);
    }

    @Test
    void setActive_用jdbctemplate直更jsonb() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        svc.setActive("KB", List.of(5L), false);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jt).update(sql.capture(), eq(false), eq(VectorStoreService.docId("KB", 5L).toString()));
        assertTrue(sql.getValue().contains("jsonb_set"), sql.getValue());
        assertTrue(sql.getValue().contains("WHERE id = ?::uuid"), sql.getValue());
    }

    @Test
    void deleteByRef_逐引用物理删() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(jt.update(anyString(), anyString())).thenReturn(1);
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());
        svc.deleteByRef("NEWS", List.of(1L, 2L));
        verify(jt, times(2)).update(anyString(), anyString());
    }

    @Test
    void searchDomains_委托similaritySearch_空域短路() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStore vs = mockStore(jt);
        VectorStoreService svc = new VectorStoreService(vs, new ObjectMapper());

        svc.searchDomains(List.of(), "q", 32, 0, "m1");
        verify(vs, times(0)).similaritySearch(any(SearchRequest.class));

        svc.searchDomains(List.of(VectorDomain.NEWS.name()), "q", 32, 0, "m1");
        org.mockito.ArgumentCaptor<SearchRequest> req = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(req.capture());
        assertEquals(32, req.getValue().getTopK());
        assertEquals(0.0, req.getValue().getSimilarityThreshold(), 1e-9);
        assertTrue(req.getValue().hasFilterExpression(), "必须带 domain/active/model 过滤");
    }

    @Test
    void searchByModel_空modelId短路() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStore vs = mockStore(jt);
        VectorStoreService svc = new VectorStoreService(vs, new ObjectMapper());
        svc.searchByModel(null, "q", 8, 0, "m1");
        verify(vs, times(0)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void searchImages_refIds白名单与门槛() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStore vs = mockStore(jt);
        VectorStoreService svc = new VectorStoreService(vs, new ObjectMapper());
        svc.searchImages("海报", List.of(7L, 8L), 0.3, 10, "m1");
        org.mockito.ArgumentCaptor<SearchRequest> req = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(req.capture());
        assertEquals(0.3, req.getValue().getSimilarityThreshold(), 1e-9);
        assertTrue(req.getValue().hasFilterExpression());
        assertEquals(10, req.getValue().getTopK());
    }
}
