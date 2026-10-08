package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.SerperSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSearchRouter.extract 缺陷修复单测(10-04-web-followup-budget C-R7/AC-C7)。
 *
 * <p>旧实现按 {@code WebProvider.values()} 枚举声明序遍历且不受 {@code webAllowed} 约束;
 * 修复后按快照 order 遍历 + 尊重 webAllowed。默认 order 下行为等价。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSearchRouterExtractTest {

    @Mock TavilySearchTool tavily;
    @Mock SearxngSearchTool searxng;
    @Mock SerperSearchTool serper;

    private WebSearchRouter router() {
        return new WebSearchRouter(tavily, searxng, serper);
    }

    /** 按快照 order 遍历:order=SEARXNG,TAVILY → 优先 SEARXNG(而非枚举序 TAVILY 优先)。 */
    @Test
    void 按快照order遍历_SEARXNG优先于TAVILY() {
        when(searxng.available()).thenReturn(true);
        when(tavily.available()).thenReturn(true);
        when(searxng.extract(any(), anyString()))
                .thenReturn(List.of(SearchTool.SearchHit.webContent("SEARXNG", "https://s.com/a", "S正文")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("SEARXNG,TAVILY"), true, 1L, 5);
        List<SearchTool.SearchHit> got = router().extract("q", List.of("https://s.com/a"), snap);
        assertEquals("S正文", got.get(0).content());
        verify(searxng).extract(any(), anyString());
        verify(tavily, never()).extract(any(), anyString());
    }

    /** webAllowed=false → 不发起任何 extract 请求(修复旧实现付费 extract 不受门控的缺陷)。 */
    @Test
    void webAllowed关闭_不发起extract() {
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), false, 1L, 5);
        assertTrue(router().extract("q", List.of("https://x.com/a"), snap).isEmpty());
        verify(tavily, never()).extract(any(), anyString());
        verify(searxng, never()).extract(any(), anyString());
    }

    /** 默认 order(TAVILY,SEARXNG)+ 放行 → 首选 TAVILY,行为与旧默认等价。 */
    @Test
    void 默认order_行为等价() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.extract(any(), anyString()))
                .thenReturn(List.of(SearchTool.SearchHit.webContent("TAVILY", "https://t.com/a", "T正文")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);
        List<SearchTool.SearchHit> got = router().extract("q", List.of("https://t.com/a"), snap);
        assertEquals("T正文", got.get(0).content());
        verify(searxng, never()).extract(any(), anyString());
    }

    /** 兼容 2 参重载仍可用(旧调用方零回归)。 */
    @Test
    void 兼容两参重载_仍可用() {
        when(tavily.available()).thenReturn(true);
        when(tavily.extract(any(), anyString()))
                .thenReturn(List.of(SearchTool.SearchHit.webContent("TAVILY", "https://t.com/a", "T正文")));
        assertEquals("T正文", router().extract("q", List.of("https://t.com/a")).get(0).content());
    }
}
