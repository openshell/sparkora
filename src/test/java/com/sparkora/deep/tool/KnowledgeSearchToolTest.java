package com.sparkora.deep.tool;

import com.sparkora.car.service.CarRagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeSearchTool 来源类型分流单测(10-05-source-web-fusion F-R1 / AC-F7):
 * user-source 返回 SOURCE、byd-news 保持 KB(零回归)。
 */
class KnowledgeSearchToolTest {

    private static CarRagService.Citation cite(String source, String sourceType) {
        return new CarRagService.Citation(source, "标题", "NEWS_BODY", 0.8, "块文本",
                null, sourceType, "销量数据");
    }

    /** 10-09 M：带 url/authorityTier 的引用（供 F-R3 去重 / F-R4 分档）。 */
    private static CarRagService.Citation citeMeta(String source, String sourceType, String url, String tier) {
        return new CarRagService.Citation(source, "标题", "NEWS_BODY", 0.8, "块文本",
                null, sourceType, "销量数据", url, tier);
    }

    private static CarRagService.RagResult ok(CarRagService.Citation... cites) {
        return new CarRagService.RagResult(CarRagService.RagStatus.OK, "ctx", cites.length, 0.8, "",
                List.of(cites));
    }

    private static List<SearchTool.SearchHit> searchWith(CarRagService.Citation... cites) {
        CarRagService rag = mock(CarRagService.class);
        when(rag.retrieveForGeneration(anyString(), anyInt(), any())).thenReturn(ok(cites));
        return new KnowledgeSearchTool(rag).search("q", 8, List.of());
    }

    /** 用户采集源(user-source)→ SOURCE,不再误标 KB(0.9)。 */
    @Test
    void userSource命中_返回SOURCE类型() {
        List<SearchTool.SearchHit> hits = searchWith(cite("NEWS", "user-source"));
        assertEquals(1, hits.size());
        assertEquals("SOURCE", hits.get(0).type());
        assertEquals("user-source", hits.get(0).sourceType());
        // gasgoo-ranking 等不可计独立交叉来源的 crossCounted=false 需透传
        assertEquals(Boolean.FALSE, searchWith(cite("NEWS", "gasgoo-ranking")).get(0).crossCounted());
        assertEquals(Boolean.TRUE, searchWith(cite("NEWS", "gasgoo-announce")).get(0).crossCounted());
    }

    /** 10-09 M：SOURCE 命中把 Citation 的 url/authorityTier 透传到 SearchHit（否则 F-R3/F-R4 空转）。 */
    @Test
    void userSource命中_透传url与authorityTier() {
        List<SearchTool.SearchHit> hits = searchWith(citeMeta("NEWS", "gasgoo-announce",
                "https://gasgoo.example/a", "industry"));
        assertEquals(1, hits.size());
        assertEquals("SOURCE", hits.get(0).type());
        assertEquals("https://gasgoo.example/a", hits.get(0).url(), "url 必须透传到 SearchHit(供 F-R3)");
        assertEquals("industry", hits.get(0).authorityTier(), "authorityTier 必须透传到 SearchHit(供 F-R4)");
    }

    /** BYD 官方新闻（byd-news）仍走 KB 分支，不携 url/authorityTier（BYD 逐位等价）。 */
    @Test
    void bydNews命中_url与authorityTier不泄露() {
        List<SearchTool.SearchHit> hits = searchWith(citeMeta("NEWS", "byd-news",
                "https://news.example/byd", "official"));
        assertEquals("KB", hits.get(0).type());
        assertNull(hits.get(0).url(), "KB 命中不透传 url");
        assertNull(hits.get(0).authorityTier(), "KB 命中不透传 authorityTier");
    }

    /** BYD 官方新闻(byd-news)→ 仍 KB(逐位等价现状)。 */
    @Test
    void bydNews命中_保持KB类型() {
        List<SearchTool.SearchHit> hits = searchWith(cite("NEWS", "byd-news"));
        assertEquals(1, hits.size());
        assertEquals("KB", hits.get(0).type());
        assertNull(hits.get(0).sourceType(), "KB 命中不透传 sourceType");
    }

    /** sourceType 缺省兜底(存量未打标块)→ KB,零回归。 */
    @Test
    void sourceType缺省_兜底KB() {
        assertEquals("KB", searchWith(cite("NEWS", null)).get(0).type());
        assertEquals("KB", searchWith(cite("NEWS", "")).get(0).type());
    }

    /** 非 NEWS 域(CAR/KB)不受影响,保持 KB。 */
    @Test
    void 非NEWS域_保持KB() {
        assertEquals("KB", searchWith(cite("CAR", "user-source")).get(0).type());
        assertEquals("KB", searchWith(cite("KB", "user-source")).get(0).type());
    }
}
