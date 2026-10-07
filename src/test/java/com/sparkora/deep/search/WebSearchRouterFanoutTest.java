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
 * WebSearchRouter PRIMARY_FANOUT 多源并行聚合单测(10-04-web-fanout-merge B-R2/AC-B2/AC-B3)。
 * Mockito 不连网、不付费。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSearchRouterFanoutTest {

    @Mock TavilySearchTool tavily;
    @Mock SearxngSearchTool searxng;
    @Mock SerperSearchTool serper;

    private WebSearchRouter router() {
        return new WebSearchRouter(tavily, searxng, serper);
    }

    private static WebSearchSnapshot snapshot(String order, boolean allowed) {
        return WebSearchSnapshot.of(WebProviderOrder.parse(order), allowed, 1L, 5);
    }

    private static WebSearchSnapshot fanout(String order, List<WebProvider> primary) {
        return WebSearchSnapshot.of(WebProviderOrder.parse(order), true, 1L, 5,
                SearchStrategy.PRIMARY_FANOUT, primary, List.of(), List.of());
    }

    private static SearchTool.SearchHit web(String provider, String title, String url) {
        return SearchTool.SearchHit.web(provider, title, url, "s");
    }

    // ===== B-R1:默认 FIRST_HIT 零回归 =====

    /** 默认策略(不设 web-fanout)行为与现状逐位等价:首个命中即停,不调后备。 */
    @Test
    void 默认FIRST_HIT_短路语义与现状等价() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(web("TAVILY", "t", "https://t.com/a")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), true, 1L, 5,
                SearchStrategy.FIRST_HIT, List.of(WebProvider.SEARXNG), List.of(), List.of());
        WebSearchOutcome out = router().search("q", 5, snap);
        assertTrue(out.hasHits());
        assertEquals(WebProvider.TAVILY, out.usedProvider());
        assertEquals(List.of(WebProvider.TAVILY), out.usedProviders());
        verify(searxng, never()).search(anyString(), anyInt());
    }

    // ===== B-R2:primary 分组 =====

    /** primary 组按配置正确选取:默认含 SEARXNG,可与 order 交集并行调用。 */
    @Test
    void primary分组_按配置集合与order交集() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(web("TAVILY", "t", "https://t.com/a")));
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(web("SEARXNG", "s", "https://s.com/a")));
        // primary={TAVILY,SEARXNG},order 含两者 → 两者都调用(多源聚合,不等首个)
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY, WebProvider.SEARXNG)));
        assertTrue(out.hasHits());
        verify(tavily).search(anyString(), anyInt());
        verify(searxng).search(anyString(), anyInt());
        assertEquals(2, out.usedProviders().size(), "两个 primary 均采信");
    }

    /** primary 可从配置移除:仅 TAVILY 进 primary → 不调 SEARXNG。 */
    @Test
    void primary可移除_只调用配置内的provider() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(web("TAVILY", "t", "https://t.com/a")));
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY)));
        assertEquals(1, out.usedProviders().size());
        verify(searxng, never()).search(anyString(), anyInt());
    }

    /** primary 为空(配置集合与 order 无交集)→ 整体回落 FIRST_HIT(SearxNG-only 部署逐位不变)。 */
    @Test
    void primary为空_整体回落FIRST_HIT() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(web("TAVILY", "t", "https://t.com/a")));
        // primary 只配 SERPER,但 order 无 SERPER → primary 空 → firstHit over order
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.SERPER)));
        assertEquals(WebProvider.TAVILY, out.usedProvider());
        verify(searxng, never()).search(anyString(), anyInt());
    }

    /** SearxNG-only 部署(DEEP 默认可能只配 SearxNG)fanout 下逐位不变:primary 空 → firstHit。 */
    @Test
    void SearxNGOnly部署_fanout下行为不变() {
        when(searxng.available()).thenReturn(true);
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(web("SEARXNG", "s", "https://s.com/a")));
        WebSearchOutcome out = router().search("q", 5,
                fanout("SEARXNG", List.of(WebProvider.SEARXNG)));
        assertEquals(WebProvider.SEARXNG, out.usedProvider());
        assertEquals(List.of(WebProvider.SEARXNG), out.usedProviders());
    }

    // ===== B-R3/AC-B3:异常隔离 =====

    /** 单 provider 异常不影响其他;异常项记 REASON_ERROR 且不含异常文本。 */
    @Test
    void 单provider异常_其余仍产出_不泄露异常文本() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenThrow(new RuntimeException("boom tvly-123"));
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(web("SEARXNG", "s", "https://s.com/a")));
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY, WebProvider.SEARXNG)));
        assertTrue(out.hasHits(), "SearxNG 仍产出");
        WebSearchOutcome.Attempt tvly = out.attempts().stream()
                .filter(a -> a.provider() == WebProvider.TAVILY).findFirst().orElseThrow();
        assertEquals(WebSearchRouter.REASON_ERROR, tvly.fallbackReason());
        assertFalse(tvly.fallbackReason().contains("tvly-123"), "降级原因不得含异常文本");
    }

    /** UNCONFIGURED provider 跳过并记录(不影响 primary 其他 provider)。 */
    @Test
    void UNCONFIGURED_跳过并记录() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(false);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(web("TAVILY", "t", "https://t.com/a")));
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY, WebProvider.SEARXNG)));
        verify(searxng, never()).search(anyString(), anyInt());
        WebSearchOutcome.Attempt sx = out.attempts().stream()
                .filter(a -> a.provider() == WebProvider.SEARXNG).findFirst().orElseThrow();
        assertEquals(WebSearchRouter.REASON_UNCONFIGURED, sx.fallbackReason());
    }

    /** primary 全空 → fallback 组按短路兜底产出。 */
    @Test
    void primary全空_fallback兜底() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of());   // primary 空结果
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(web("SEARXNG", "s", "https://s.com/a")));
        // primary={TAVILY};TAVILY 空 → fallback={SEARXNG}
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY)));
        assertEquals(WebProvider.SEARXNG, out.usedProvider(), "fallback 兜底产出");
        assertTrue(out.hasHits());
    }

    // ===== B-R2a/AC-B8:SearXNG 质量门 =====

    /** SearXNG 命中 bilibili/sogou 跳转/视频页被过滤,正文页保留。 */
    @Test
    void SearXNG质量门_黑名单与跳转页被过滤() {
        when(searxng.available()).thenReturn(true);
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(
                web("SEARXNG", "视频", "https://www.bilibili.com/video/BV1"),
                web("SEARXNG", "跳转", "https://weixin.sogou.com/link?url=abc"),
                web("SEARXNG", "视频页", "https://news.example.com/video/123"),
                web("SEARXNG", "正文", "https://news.example.com/article/1")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("SEARXNG"), true, 1L, 5,
                SearchStrategy.PRIMARY_FANOUT, List.of(WebProvider.SEARXNG),
                List.of("bilibili.com", "weixin.sogou.com"), List.of());
        WebSearchOutcome out = router().search("q", 5, snap);
        assertEquals(1, out.hits().size(), "只保留正文页");
        assertEquals("https://news.example.com/article/1", out.hits().get(0).url());
    }

    /** 非 SearXNG provider 不施加质量门(bilibili 结果保留,零回归)。 */
    @Test
    void 非SearXNGprovider_不施加质量门() {
        when(tavily.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(
                web("TAVILY", "视频", "https://www.bilibili.com/video/BV1")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY"), true, 1L, 5,
                SearchStrategy.PRIMARY_FANOUT, List.of(WebProvider.TAVILY),
                List.of("bilibili.com"), List.of());
        WebSearchOutcome out = router().search("q", 5, snap);
        assertEquals(1, out.hits().size(), "Tavily 结果不受 SearXNG 质量门影响");
    }

    // ===== B-R5:cross validation observable =====

    /** attempts[].witnessTotal:同 URL 被多 provider 命中时可观测到交叉见证。 */
    @Test
    void witnessTotal_可观测交叉验证() {
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(
                web("TAVILY", "t1", "https://x.com/a"),
                web("TAVILY", "t2", "https://x.com/b")));
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(web("SEARXNG", "s", "https://x.com/a")));
        WebSearchOutcome out = router().search("q", 5,
                fanout("TAVILY,SEARXNG", List.of(WebProvider.TAVILY, WebProvider.SEARXNG)));
        WebSearchOutcome.Attempt tvly = out.attempts().stream()
                .filter(a -> a.provider() == WebProvider.TAVILY).findFirst().orElseThrow();
        assertEquals(1, tvly.witnessTotal(), "Tavily 有 1 条被 SearxNG 见证");
        WebSearchOutcome.Attempt sx = out.attempts().stream()
                .filter(a -> a.provider() == WebProvider.SEARXNG).findFirst().orElseThrow();
        assertEquals(1, sx.witnessTotal(), "SearxNG 的唯一命中被 Tavily 见证");
    }

    /** 开关关闭 → 不发起任何请求(fanout 同样受 webAllowed 门控)。 */
    @Test
    void fanout开关关闭_不发起请求() {
        WebSearchOutcome out = router().search("q", 5, WebSearchSnapshot.of(
                WebProviderOrder.parse("TAVILY,SEARXNG"), false, 1L, 5,
                SearchStrategy.PRIMARY_FANOUT, List.of(WebProvider.TAVILY, WebProvider.SEARXNG),
                List.of(), List.of()));
        assertFalse(out.hasHits());
        assertNull(out.usedProvider());
        verify(tavily, never()).search(anyString(), anyInt());
        verify(searxng, never()).search(anyString(), anyInt());
    }

    /** 垂直搜索在 fanout 下也走 searchVertical。 */
    @Test
    void fanout_垂直走searchVertical() {
        when(serper.available()).thenReturn(true);
        when(serper.searchVertical(anyString(), eq("news"), anyInt()))
                .thenReturn(List.of(web("SERPER", "n", "https://n.com/1")));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("SERPER"), true, 1L, 5,
                SearchStrategy.PRIMARY_FANOUT, List.of(WebProvider.SERPER), List.of(), List.of());
        WebSearchOutcome out = router().searchVertical("q", 5, snap, "news");
        assertTrue(out.hasHits());
        verify(serper).searchVertical("q", "news", 5);
        verify(serper, never()).search(anyString(), anyInt());
    }
}
