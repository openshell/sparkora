package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebResultNormalizer URL 治理单测(09-25-brief-web-search R8/AC-07)。
 */
class WebResultNormalizerTest {

    private static SearchTool.SearchHit web(String title, String url) {
        return SearchTool.SearchHit.web("TAVILY", title, url, "snippet");
    }

    @Test
    void 协议校验_仅接受http与https绝对URL() {
        assertNull(WebResultNormalizer.normalizeUrl(""));
        assertNull(WebResultNormalizer.normalizeUrl("   "));
        assertNull(WebResultNormalizer.normalizeUrl("ftp://example.com/a"));
        assertNull(WebResultNormalizer.normalizeUrl("javascript:alert(1)"));
        assertNull(WebResultNormalizer.normalizeUrl("/relative/path"));
        assertNull(WebResultNormalizer.normalizeUrl("example.com/no-scheme"));
        assertEquals("http://example.com/a", WebResultNormalizer.normalizeUrl("http://example.com/a"));
        assertEquals("https://example.com/a", WebResultNormalizer.normalizeUrl("https://example.com/a"));
    }

    @Test
    void 规范化_去fragment_小写scheme与host_保留path与query() {
        assertEquals("https://example.com/p?q=1",
                WebResultNormalizer.normalizeUrl("HTTPS://Example.COM/p?q=1#frag"));
        assertEquals("http://example.com/",
                WebResultNormalizer.normalizeUrl("http://example.com"));
    }

    @Test
    void 去重_重复URL只保留一条() {
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(List.of(
                web("a", "https://x.com/1"),
                web("b", "https://x.com/1#section"),   // fragment 变体视为同一条
                web("c", "https://x.com/2")
        ), 10);
        assertEquals(2, hits.size());
        assertEquals("https://x.com/1", hits.get(0).url());
        assertEquals("W1", hits.get(0).sourceId());
        assertEquals("W2", hits.get(1).sourceId());
    }

    @Test
    void 非法URL_被剔除_有效条目仍分配稳定sourceId() {
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(List.of(
                web("bad", "not-a-url"),
                web("ok", "https://x.com/a"),
                web("bad2", "")
        ), 10);
        assertEquals(1, hits.size());
        assertEquals("W1", hits.get(0).sourceId());
        assertEquals("ok", hits.get(0).title());
    }

    @Test
    void 截断_不超过上限() {
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(List.of(
                web("a", "https://x.com/1"),
                web("b", "https://x.com/2"),
                web("c", "https://x.com/3")
        ), 2);
        assertEquals(2, hits.size());
    }

    @Test
    void provider_回填与sourceId_进入SearchHit() {
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(
                List.of(web("t", "https://x.com/a")), 5);
        SearchTool.SearchHit sh = hits.get(0).toSearchHit();
        assertEquals("WEB", sh.type());
        assertEquals("W1", sh.sourceId());
        assertEquals("TAVILY", sh.provider());
        assertEquals("https://x.com/a", sh.url());
    }

    @Test
    void 空输入_返回空不抛() {
        assertTrue(WebResultNormalizer.normalize(null, 5).isEmpty());
        assertTrue(WebResultNormalizer.normalize(List.of(), 5).isEmpty());
    }

    /** R2(09-27-tavily-extract-kind-hypotheses):content 正文载体透传(不混入 snippet 语义)。 */
    @Test
    void content正文载体_经WebHit透传到SearchHit() {
        SearchTool.SearchHit raw = SearchTool.SearchHit.web("TAVILY", "t", "https://x.com/a", "摘要", "正文片段");
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(List.of(raw), 5);
        assertEquals("正文片段", hits.get(0).content(), "content 应经 WebHit 透传");
        SearchTool.SearchHit sh = hits.get(0).toSearchHit();
        assertEquals("正文片段", sh.content(), "toSearchHit 应透传 content");
        assertEquals("摘要", sh.snippet(), "snippet 语义不受 content 影响");
    }

    /** R2:旧 5 参构造器/旧 web(...)无 content → null(向后兼容)。 */
    @Test
    void 旧构造器_无content_兼容为null() {
        List<WebResultNormalizer.WebHit> hits = WebResultNormalizer.normalize(
                List.of(web("t", "https://x.com/a")), 5);
        assertNull(hits.get(0).content());
        assertNull(hits.get(0).toSearchHit().content());
    }
}
