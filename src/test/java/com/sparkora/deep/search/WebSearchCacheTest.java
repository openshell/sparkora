package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchCache 批次内缓存单测(10-04-web-followup-budget C-R5/AC-C5)。
 *
 * <p>批次内相同 {@code provider+query+vertical+maxResults} 二次命中(cacheHit);批次结束释放;
 * 空结果不缓存;TTL 过期失效。
 */
class WebSearchCacheTest {

    private static final SearchTool.SearchHit HIT =
            SearchTool.SearchHit.web("TAVILY", "t", "https://x.com/a", "s");

    @Test
    void 相同key_二次命中并计数() {
        WebSearchCache cache = new WebSearchCache(600000);
        assertNull(cache.get(WebProvider.TAVILY, "价格", null, 5));
        cache.put(WebProvider.TAVILY, "价格", null, 5, List.of(HIT));
        List<SearchTool.SearchHit> got = cache.get(WebProvider.TAVILY, "价格", null, 5);
        assertNotNull(got);
        assertEquals(1, got.size());
        assertEquals(1, cache.hits(), "命中一次 cacheHit 计数 +1");
    }

    @Test
    void 不同维度_不命中() {
        WebSearchCache cache = new WebSearchCache(600000);
        cache.put(WebProvider.TAVILY, "价格", null, 5, List.of(HIT));
        assertNull(cache.get(WebProvider.SEARXNG, "价格", null, 5), "provider 不同");
        assertNull(cache.get(WebProvider.TAVILY, "续航", null, 5), "query 不同");
        assertNull(cache.get(WebProvider.TAVILY, "价格", "news", 5), "vertical 不同");
        assertNull(cache.get(WebProvider.TAVILY, "价格", null, 10), "maxResults 不同");
    }

    @Test
    void query规范化_大小写空白等价命中() {
        WebSearchCache cache = new WebSearchCache(600000);
        cache.put(WebProvider.TAVILY, " Price  Test ", null, 5, List.of(HIT));
        assertNotNull(cache.get(WebProvider.TAVILY, "price test", null, 5), "trim/小写/压缩空白后应命中");
    }

    @Test
    void 空结果_不缓存() {
        WebSearchCache cache = new WebSearchCache(600000);
        cache.put(WebProvider.TAVILY, "价格", null, 5, List.of());
        assertEquals(0, cache.size());
        assertNull(cache.get(WebProvider.TAVILY, "价格", null, 5));
    }

    @Test
    void 批次结束_缓存与计数全部释放() {
        WebSearchCache cache = new WebSearchCache(600000);
        cache.put(WebProvider.TAVILY, "价格", null, 5, List.of(HIT));
        cache.get(WebProvider.TAVILY, "价格", null, 5);
        assertTrue(cache.size() > 0);
        cache.release();
        assertEquals(0, cache.size(), "释放后不留跨批次数据");
        assertEquals(0, cache.hits());
        assertNull(cache.get(WebProvider.TAVILY, "价格", null, 5));
    }

    @Test
    void TTL过期_失效不命中() throws Exception {
        WebSearchCache cache = new WebSearchCache(1);   // 1ms TTL
        cache.put(WebProvider.TAVILY, "价格", null, 5, List.of(HIT));
        Thread.sleep(10);
        assertNull(cache.get(WebProvider.TAVILY, "价格", null, 5), "过期应失效");
    }
}
