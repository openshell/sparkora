package com.sparkora.deep.tool;

import com.sparkora.deep.search.WebProvider;
import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.search.WebSearchOutcome;
import com.sparkora.deep.search.WebSearchRouter;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.deep.search.WebProviderOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SearchToolCallbacks 工厂单测(C3,10-02):名称稳定 / 可用性门控 / KB 与 WEB 委托 / 异常降级。
 * 纯 Mockito,不连网、不付费、不启动 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchToolCallbacksTest {

    @Mock KnowledgeSearchTool kbTool;
    @Mock WebSearchRouter webRouter;

    private static WebSearchSnapshot snapshot(boolean allowed) {
        return WebSearchSnapshot.of(WebProviderOrder.defaults(), allowed, 1L, 5);
    }

    private static String kbInput(String query, int max) {
        return "{\"query\":\"" + query + "\",\"maxResults\":" + max + "}";
    }

    // ===== 名称稳定 =====

    @Test
    void forTools_KB名称稳定为kb_search() {
        when(kbTool.available()).thenReturn(true);
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter).forTools(List.of("KB"));
        assertEquals(1, cbs.length);
        assertEquals(SearchToolCallbacks.NAME_KB, cbs[0].getToolDefinition().name());
    }

    @Test
    void forTools_TAVILY与SEARXNG均映射到web_search且不重复() {
        when(webRouter.configured(any())).thenReturn(true);
        SearchToolCallbacks factory = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true));
        ToolCallback[] cbs = factory.forTools(List.of("TAVILY", "SEARXNG"));
        assertEquals(1, cbs.length, "同一 router 的两种 provider 名只暴露一个 web_search");
        assertEquals(SearchToolCallbacks.NAME_WEB, cbs[0].getToolDefinition().name());
    }

    @Test
    void forTools_WEB别名映射到web_search() {
        // 流水线词汇 applySettingGates/parseTools 用 "WEB"(非 TAVILY/SEARXNG),别名须同样生效
        when(webRouter.configured(any())).thenReturn(true);
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("WEB"));
        assertEquals(1, cbs.length);
        assertEquals(SearchToolCallbacks.NAME_WEB, cbs[0].getToolDefinition().name());
    }

    @Test
    void forTools_空或null_返回空数组() {
        SearchToolCallbacks factory = new SearchToolCallbacks(kbTool, webRouter);
        assertEquals(0, factory.forTools(null).length);
        assertEquals(0, factory.forTools(List.of()).length);
        assertEquals(0, factory.forTools(List.of("UNKNOWN")).length);
    }

    // ===== 可用性门控 =====

    @Test
    void forTools_KB不可用_不暴露() {
        when(kbTool.available()).thenReturn(false);
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter).forTools(List.of("KB"));
        assertEquals(0, cbs.length);
    }

    @Test
    void forTools_WEB开关关闭_不暴露且不探测provider配置() {
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(false))
                .forTools(List.of("TAVILY"));
        assertEquals(0, cbs.length);
        verify(webRouter, never()).configured(any());
    }

    @Test
    void forTools_WEB无provider配置_不暴露() {
        when(webRouter.configured(WebProvider.TAVILY)).thenReturn(false);
        when(webRouter.configured(WebProvider.SEARXNG)).thenReturn(false);
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("TAVILY"));
        assertEquals(0, cbs.length);
    }

    @Test
    void forTools_KB与WEB同时请求_各自暴露() {
        when(kbTool.available()).thenReturn(true);
        when(webRouter.configured(WebProvider.TAVILY)).thenReturn(true);
        ToolCallback[] cbs = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("KB", "TAVILY"));
        assertEquals(2, cbs.length);
    }

    // ===== KB 委托 =====

    @Test
    void KB_委托search且透传query与maxResults() {
        when(kbTool.available()).thenReturn(true);
        when(kbTool.search(eq("比亚迪"), eq(3), eq(List.of())))
                .thenReturn(List.of(SearchTool.SearchHit.kb("车型·MODEL_INFO", "汉EV", 1L, "价格区间…", 0.9)));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter).forTools(List.of("KB"))[0];

        String out = cb.call(kbInput("比亚迪", 3));

        verify(kbTool).search("比亚迪", 3, List.of());
        assertTrue(out.contains("KB"), "输出保留 type");
        assertTrue(out.contains("汉EV"), "输出保留标题");
    }

    @Test
    void KB_带锚点_走三参重载() {
        when(kbTool.available()).thenReturn(true);
        when(kbTool.search(eq("q"), eq(8), eq(List.of(7L))))
                .thenReturn(List.of(SearchTool.SearchHit.kb("t", "m", 1L, "s", 0.5)));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter, List.of(7L), null).forTools(List.of("KB"))[0];

        cb.call(kbInput("q", 8));

        verify(kbTool).search("q", 8, List.of(7L));
    }

    @Test
    void KB_maxResults缺省_默认8() {
        when(kbTool.available()).thenReturn(true);
        when(kbTool.search(eq("q"), eq(8), any())).thenReturn(List.of());
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter).forTools(List.of("KB"))[0];

        cb.call("{\"query\":\"q\"}");

        verify(kbTool).search("q", 8, List.of());
    }

    // ===== WEB 委托(经 router,治理透传) =====

    @Test
    void WEB_经router且结果保留sourceId_url_provider() {
        when(webRouter.configured(WebProvider.TAVILY)).thenReturn(true);
        when(webRouter.search(eq("q"), eq(5), any()))
                .thenReturn(new WebSearchOutcome(
                        List.of(new WebHit("W1", "标题", "https://x.com/a", "摘要", "TAVILY")),
                        WebProvider.TAVILY, List.of()));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("TAVILY"))[0];

        String out = cb.call(kbInput("q", 5));

        verify(webRouter).search(eq("q"), eq(5), eq(snapshot(true)));
        assertTrue(out.contains("W1"), "保留稳定 sourceId");
        assertTrue(out.contains("https://x.com/a"), "保留 url");
        assertTrue(out.contains("TAVILY"), "保留 provider");
    }

    @Test
    void WEB_无命中_返回中性提示串() {
        when(webRouter.configured(WebProvider.SEARXNG)).thenReturn(true);
        when(webRouter.search(anyString(), anyInt(), any()))
                .thenReturn(new WebSearchOutcome(List.of(), null, List.of()));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("SEARXNG"))[0];

        String out = cb.call(kbInput("q", 5));

        // 默认 ToolCallResultConverter 会把 String 结果 JSON 序列化(含引号),断言内容即可
        assertTrue(out.contains("无检索结果。"));
    }

    // ===== 异常降级(绝不抛出) =====

    @Test
    void KB_内部异常_返回提示串不抛出且不泄露异常文本() {
        when(kbTool.available()).thenReturn(true);
        when(kbTool.search(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("boom tvly-123"));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter).forTools(List.of("KB"))[0];

        String out = assertDoesNotThrow(() -> cb.call(kbInput("q", 8)));

        assertTrue(out.contains("暂不可用"));
        assertFalse(out.contains("boom"), "降级文案不得含异常原文");
        assertFalse(out.contains("tvly-123"));
    }

    @Test
    void WEB_内部异常_返回提示串不抛出且不泄露异常文本() {
        when(webRouter.configured(WebProvider.TAVILY)).thenReturn(true);
        when(webRouter.search(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("boom sk-secret"));
        ToolCallback cb = new SearchToolCallbacks(kbTool, webRouter, List.of(), snapshot(true))
                .forTools(List.of("TAVILY"))[0];

        String out = assertDoesNotThrow(() -> cb.call(kbInput("q", 5)));

        assertTrue(out.contains("暂不可用"));
        assertFalse(out.contains("boom"));
        assertFalse(out.contains("sk-secret"));
    }
}
