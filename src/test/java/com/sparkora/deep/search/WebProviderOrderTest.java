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

    // ===== 10-04-serper-provider A-R7:三值策略标签 =====

    @Test
    void SERPER加入_解析成功且标签为PRIMARY_FANOUT() {
        WebProviderOrder o = WebProviderOrder.parse("SERPER,TAVILY");
        assertEquals(List.of(WebProvider.SERPER, WebProvider.TAVILY), o.providers());
        assertEquals(WebProviderOrder.PRIMARY_FANOUT, o.strategyLabel());
        assertEquals("SERPER,TAVILY", o.raw());
    }

    @Test
    void 默认顺序不变_仍TAVILY_SEARXNG零回归() {
        assertEquals("TAVILY,SEARXNG", WebProviderOrder.DEFAULT_RAW, "默认值不变是零回归前提");
        assertEquals(List.of(WebProvider.TAVILY, WebProvider.SEARXNG), WebProviderOrder.defaults().providers());
        assertEquals("TAVILY_FIRST", WebProviderOrder.defaults().strategyLabel(), "纯 legacy 顺序标签逐字不变");
    }

    @Test
    void SERPER大小写不敏感() {
        assertEquals(WebProvider.SERPER, WebProvider.from(" serper "));
        assertEquals(WebProviderOrder.PRIMARY_FANOUT, WebProviderOrder.parse("tavily,serper").strategyLabel());
    }

    @Test
    void SERPER与SEARXNG组合_也回落PRIMARY_FANOUT() {
        assertEquals(WebProviderOrder.PRIMARY_FANOUT, WebProviderOrder.parse("SEARXNG,SERPER").strategyLabel());
    }
}
