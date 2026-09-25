package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSearchRouter 策略路由单测(09-25-brief-web-search R1/R2/R5,AC-02/03/04/05)。
 * Mockito 不连网、不付费。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSearchRouterTest {

    @Mock TavilySearchTool tavily;
    @Mock SearxngSearchTool searxng;

    private WebSearchRouter router() {
        return new WebSearchRouter(tavily, searxng);
    }

    private static WebSearchSnapshot snapshot(String order, boolean allowed) {
        return WebSearchSnapshot.of(WebProviderOrder.parse(order), allowed, 1L, 5);
    }

    @Test
    void 默认TAVILY优先_命中则SearxNG调用次数为0() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("TAVILY", "t", "https://t.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertTrue(out.hasHits());
        assertEquals(WebProvider.TAVILY, out.usedProvider());
        verify(searxng, never()).search(anyString(), anyInt());   // AC-02
    }

    @Test
    void 首选Tavily空结果_按策略降级一次SearxNG() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of());
        when(searxng.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertTrue(out.hasHits());
        assertEquals(WebProvider.SEARXNG, out.usedProvider());
        assertEquals(WebSearchRouter.REASON_EMPTY, out.attempts().get(0).fallbackReason());   // AC-03
        verify(tavily).search(anyString(), anyInt());
        verify(searxng).search(anyString(), anyInt());
    }

    @Test
    void 首选异常_降级后备且不泄露异常文本() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenThrow(new RuntimeException("boom tvly-123"));
        when(searxng.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertEquals(WebSearchRouter.REASON_ERROR, out.attempts().get(0).fallbackReason());
        assertEquals(WebProvider.SEARXNG, out.usedProvider());   // AC-03
        assertFalse(out.attempts().get(0).fallbackReason().contains("tvly-123"), "降级原因不得含异常文本");
    }

    @Test
    void 结果全部无有效URL_视为无效并降级() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("TAVILY", "t", "not-a-url", "s")));
        when(searxng.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertEquals(WebSearchRouter.REASON_INVALID_URL, out.attempts().get(0).fallbackReason());
        assertEquals(WebProvider.SEARXNG, out.usedProvider());   // AC-03
    }

    @Test
    void 未配置provider_跳过记UNCONFIGURED() {
        when(tavily.available()).thenReturn(false);
        when(searxng.available()).thenReturn(true);
        when(searxng.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertEquals(WebSearchRouter.REASON_UNCONFIGURED, out.attempts().get(0).fallbackReason());
        verify(tavily, never()).search(anyString(), anyInt());   // AC-04/R5
        assertEquals(WebProvider.SEARXNG, out.usedProvider());
    }

    @Test
    void 开关关闭_不发起任何请求() {
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", false));
        assertFalse(out.hasHits());
        verify(tavily, never()).search(anyString(), anyInt());
        verify(searxng, never()).search(anyString(), anyInt());   // AC-04
    }

    @Test
    void SEARXNG优先策略_首选SearxNG命中_不调Tavily() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(searxng.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("SEARXNG,TAVILY", true));
        assertEquals(WebProvider.SEARXNG, out.usedProvider());
        verify(tavily, never()).search(anyString(), anyInt());   // AC-05 策略可切
    }

    @Test
    void 两路均不可用_空结果带降级原因_不抛() {
        when(tavily.available()).thenReturn(false);
        when(searxng.available()).thenReturn(false);
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertFalse(out.hasHits());
        assertNull(out.usedProvider());
        assertEquals(2, out.attempts().size());   // AC-09
    }
}
