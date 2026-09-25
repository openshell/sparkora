package com.sparkora.deep.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebProviderOrder 策略解析单测(09-25-brief-web-search R2/AC-02)。
 */
class WebProviderOrderTest {

    @Test
    void 默认策略_TAVILY优先_SearxNG兜底() {
        WebProviderOrder o = WebProviderOrder.defaults();
        assertEquals(List.of(WebProvider.TAVILY, WebProvider.SEARXNG), o.providers());
        assertEquals("TAVILY_FIRST", o.strategyLabel());
        assertEquals("TAVILY,SEARXNG", o.raw());
    }

    @Test
    void 空值_回退默认TAVILY优先() {
        assertEquals("TAVILY_FIRST", WebProviderOrder.parse(null).strategyLabel());
        assertEquals("TAVILY_FIRST", WebProviderOrder.parse("  ").strategyLabel());
    }

    @Test
    void SEARXNG优先_解析为SEARXNG_FIRST() {
        WebProviderOrder o = WebProviderOrder.parse("SEARXNG,TAVILY");
        assertEquals("SEARXNG_FIRST", o.strategyLabel());
        assertEquals(List.of(WebProvider.SEARXNG, WebProvider.TAVILY), o.providers());
    }

    @Test
    void 去重_重复provider只保留首次() {
        WebProviderOrder o = WebProviderOrder.parse("TAVILY,TAVILY,SEARXNG,tavily");
        assertEquals(List.of(WebProvider.TAVILY, WebProvider.SEARXNG), o.providers());
        assertEquals("TAVILY,SEARXNG", o.raw());
    }

    @Test
    void 大小写与空白_规范化() {
        assertEquals(List.of(WebProvider.SEARXNG, WebProvider.TAVILY),
                WebProviderOrder.parse(" searxng , tavily ").providers());
    }

    @Test
    void 未知provider_明确拒绝不静默() {
        assertThrows(IllegalArgumentException.class, () -> WebProviderOrder.parse("BOTH"));
        assertThrows(IllegalArgumentException.class, () -> WebProviderOrder.parse("TAVILY,GOOGLE"));
        assertThrows(IllegalArgumentException.class, () -> WebProvider.from(""));
    }
}
