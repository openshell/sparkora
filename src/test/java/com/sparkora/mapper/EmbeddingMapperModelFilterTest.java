package com.sparkora.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.vector.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量层模型过滤契约单测（09-27 R5/AC9；10-03 E6 旧表退役后改验单表 store）。
 *
 * <p>旧 4 表退役后，模型过滤不再由各 mapper 的注解 SQL 承载，而收敛到 {@link VectorStoreService}：
 * <ul>
 *   <li>检索过滤表达式固定带 {@code embeddingModel == 当前模型}（换模型后旧行不参与检索）；</li>
 *   <li>store 统计/差集 SQL 带 {@code metadata->>'embeddingModel'} 条件。</li>
 * </ul>
 * 不需要连库。
 */
class EmbeddingMapperModelFilterTest {

    @SuppressWarnings("unchecked")
    private static VectorStore mockStore(JdbcTemplate jt) {
        VectorStore vs = mock(VectorStore.class);
        when(vs.getNativeClient()).thenReturn(Optional.of(jt));
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        return vs;
    }

    @Test
    void 检索过滤表达式_带embeddingModel与active与domain() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        VectorStore vs = mockStore(jt);
        VectorStoreService svc = new VectorStoreService(vs, new ObjectMapper());

        svc.searchDomains(List.of("CAR", "KB"), "q", 32, 0, "current-embed");

        ArgumentCaptor<SearchRequest> req = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vs).similaritySearch(req.capture());
        assertTrue(req.getValue().hasFilterExpression(), "必须带 domain/active/embeddingModel 过滤");
        String filter = req.getValue().getFilterExpression().toString();
        assertTrue(filter.contains("embeddingModel"), filter);
        assertTrue(filter.contains("active"), filter);
        assertTrue(filter.contains("domain"), filter);
    }

    @Test
    void 车型对账SQL_按domain与embeddingModel聚合() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(jt.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());

        svc.countCarEmbeddedByModel("current-embed");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jt).queryForList(sql.capture(), anyString(), anyString());
        assertTrue(sql.getValue().contains("metadata->>'embeddingModel'"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'domain'"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'modelId'"), sql.getValue());
    }

    @Test
    void 图片补缺失差集SQL_按domain与embeddingModel过滤() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(jt.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());

        svc.refIdsByDomain("IMAGE", "current-embed");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jt).queryForList(sql.capture(), anyString(), anyString());
        assertTrue(sql.getValue().contains("metadata->>'domain'"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'embeddingModel'"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'refId'"), sql.getValue());
    }

    @Test
    void 模型聚合SQL_按domain与embeddingModel分组() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(jt.queryForList(anyString())).thenReturn(List.of());
        VectorStoreService svc = new VectorStoreService(mockStore(jt), new ObjectMapper());

        svc.embeddingModelStats();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jt).queryForList(sql.capture());
        assertTrue(sql.getValue().contains("metadata->>'embeddingModel'"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'domain'"), sql.getValue());
        assertTrue(sql.getValue().contains("GROUP BY"), sql.getValue());
    }
}
