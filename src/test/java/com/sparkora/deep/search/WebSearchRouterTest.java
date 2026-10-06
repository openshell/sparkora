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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock SerperSearchTool serper;

    private WebSearchRouter router() {
        return new WebSearchRouter(tavily, searxng, serper);
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

    // ===== R1(09-27-tavily-extract-kind-hypotheses):按 URL 抽取正文(工具抽象 + 降级) =====

    /** 空 urls → 不发起任何请求(零成本跳过)。 */
    @Test
    void extract_空urls_不调用任何provider() {
        assertTrue(router().extract("q", List.of()).isEmpty());
        assertTrue(router().extract("q", null).isEmpty());
        verify(tavily, never()).extract(any(), anyString());
        verify(searxng, never()).extract(any(), anyString());
    }

    /** 首个产出非空即采信并停止(不重复调用后备 provider)。 */
    @Test
    void extract_首选非空_不调后备() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.extract(any(), anyString()))
                .thenReturn(List.of(SearchTool.SearchHit.webContent("TAVILY", "https://t.com/a", "正文")));
        List<SearchTool.SearchHit> got = router().extract("q", List.of("https://t.com/a"));
        assertEquals(1, got.size());
        assertEquals("正文", got.get(0).content());
        verify(searxng, never()).extract(any(), anyString());
    }

    /** 首选未配置 → 跳过;首选异常 → 降级后备(不抛)。 */
    @Test
    void extract_未配置跳过_异常降级后备不抛() {
        when(searxng.available()).thenReturn(true);
        when(searxng.extract(any(), anyString()))
                .thenReturn(List.of(SearchTool.SearchHit.webContent("SEARXNG", "https://s.com/a", "后备正文")));
        // Tavily 未配置 → 跳过,直接到 SearxNG
        when(tavily.available()).thenReturn(false);
        assertEquals("后备正文", router().extract("q", List.of("https://s.com/a")).get(0).content());
        verify(tavily, never()).extract(any(), anyString());

        // Tavily 可用但抽取异常 → 降级 SearxNG
        when(tavily.available()).thenReturn(true);
        when(tavily.extract(any(), anyString())).thenThrow(new RuntimeException("boom tvly-123"));
        assertEquals("后备正文", router().extract("q", List.of("https://s.com/a")).get(0).content());
    }

    /** 全部 provider 无正文 → 空列表(降级回摘要),不抛。 */
    @Test
    void extract_全部为空_返回空列表不抛() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        assertTrue(router().extract("q", List.of("https://x.com/a")).isEmpty());
    }

    // ===== 10-04-serper-provider A:SERPER 注册 / 未配置跳过 / resultCount 实际返回数 / 垂直 =====

    /** A-A3:未配置的 SERPER 跳过并记 UNCONFIGURED,不影响 TAVILY 采信。 */
    @Test
    void SERPER未配置_跳过且不影响TAVILY() {
        when(serper.available()).thenReturn(false);
        when(tavily.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("TAVILY", "t", "https://t.com/a", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("SERPER,TAVILY", true));
        assertEquals(WebProvider.SERPER, out.attempts().get(0).provider());
        assertEquals(WebSearchRouter.REASON_UNCONFIGURED, out.attempts().get(0).fallbackReason());
        assertEquals(WebProvider.TAVILY, out.usedProvider());
        verify(serper, never()).search(anyString(), anyInt());
    }

    /** A-A6:attempts.resultCount 记 normalize 后实际命中数,而非请求条数。 */
    @Test
    void resultCount_记实际返回数而非请求数() {
        when(tavily.available()).thenReturn(true);
        // 请求 maxResults=5,provider 只回 2 条 → resultCount 必须为 2
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(
                SearchTool.SearchHit.web("TAVILY", "t1", "https://t.com/a", "s"),
                SearchTool.SearchHit.web("TAVILY", "t2", "https://t.com/b", "s")));
        WebSearchOutcome out = router().search("q", 5, snapshot("TAVILY,SEARXNG", true));
        assertEquals(2, out.attempts().get(0).resultCount(), "resultCount 必须是实际命中数,不是请求数");
    }

    /** A-R3:SERPER 垂直搜索经 searchVertical 转发(news 垂直)。 */
    @Test
    void 垂直搜索_经searchVertical转发到SERPER() {
        when(serper.available()).thenReturn(true);
        when(serper.searchVertical(anyString(), eq("news"), anyInt()))
                .thenReturn(List.of(SearchTool.SearchHit.web("SERPER", "n", "https://n.com/1", "s")));
        WebSearchOutcome out = router().searchVertical("q", 5, snapshot("SERPER", true), "news");
        assertEquals(WebProvider.SERPER, out.usedProvider());
        verify(serper).searchVertical("q", "news", 5);
        verify(serper, never()).search(anyString(), anyInt());
    }
}
