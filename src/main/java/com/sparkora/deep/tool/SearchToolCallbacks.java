package com.sparkora.deep.tool;

import com.sparkora.deep.search.WebProvider;
import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.search.WebSearchOutcome;
import com.sparkora.deep.search.WebSearchRouter;
import com.sparkora.deep.search.WebSearchSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把既有检索工具暴露为 Spring AI {@link ToolCallback} 的**按需工厂**(C3,10-02)。
 *
 * <p><b>为什么是工厂而非 Bean</b>:Spring AI 自动配置会把容器内所有 {@code ToolCallback} bean 作为
 * **所有** {@code ChatClient} 的默认工具——一旦注册为全局 bean,C1/C2 的普通对话/结构化调用会被
 * 意外注入工具、模型可能吐出 {@code tool_calls} 导致行为漂移。故本类**不标注任何 stereotype 注解**、
 * 不产出全局 {@code ToolCallback} bean;由调用方在需要「模型驱动检索」时显式 {@code new} +
 * {@link #forTools(List)} 并把返回值传给 {@code ChatClient...toolCallbacks(...)}。
 *
 * <p><b>确定性编排不变</b>:{@link com.sparkora.deep.service.SubAgentRunner#research} 的流水线
 * (KB → 策略路由 WEB → LLM 单次汇总)与本类无关、不被调用;本类仅提供增量能力层。
 *
 * <p><b>WEB 必须经 {@link WebSearchRouter}</b>:保留 provider 顺序、治理(协议校验/规范化/去重/截断)
 * 与稳定 sourceId,不得直连 {@code TavilySearchTool}/{@code SearxngSearchTool}。
 *
 * <p>工具方法内部捕获全部异常,返回可读降级提示串(不抛出),与 {@link SearchTool}「绝不抛出」约定一致;
 * 降级文案不含异常原文(可能含密钥/URL)。
 */
@Slf4j
public class SearchToolCallbacks {

    /** 工具名:KB 检索。 */
    public static final String TOOL_KB = "KB";
    /** 工具名:WEB 检索(Tavily;经 router 策略路由)。 */
    public static final String TOOL_TAVILY = "TAVILY";
    /** 工具名:WEB 检索(SearxNG;经 router 策略路由)。 */
    public static final String TOOL_SEARXNG = "SEARXNG";
    /**
     * 工具名:WEB 检索(流水线词汇)。{@code DeepResearchService.applySettingGates}/{@code parseTools}/
     * {@code ClarifyService} 的工具词汇是 {@code KB}/{@code WEB},故此处兼容 {@code WEB} 别名,
     * 避免未来直接透传流水线工具集时 WEB 被静默忽略。
     */
    public static final String TOOL_WEB = "WEB";

    /** KB callback 名(对模型暴露)。 */
    public static final String NAME_KB = "kb_search";
    /** WEB callback 名(对模型暴露;TAVILY/SEARXNG 名均映射到同一 router 调用)。 */
    public static final String NAME_WEB = "web_search";

    /** 默认 KB 返回条数(对齐 SubAgentRunner 既有 8)。 */
    private static final int DEFAULT_KB_MAX = 8;
    /** 默认 WEB 返回条数(对齐 SubAgentRunner 既有 min(5, quota))。 */
    private static final int DEFAULT_WEB_MAX = 5;

    private final KnowledgeSearchTool kbTool;
    private final WebSearchRouter webRouter;
    private final List<Long> anchors;
    private final WebSearchSnapshot snapshot;

    /**
     * @param kbTool   本地知识库工具(可空:为 null 时不暴露 KB)
     * @param webRouter WEB 策略路由(可空:为 null 时不暴露 WEB)
     * @param anchors  锚点车型 id(可空=无锚点检索)
     * @param snapshot 本次研究的策略与开关快照(webAllowed 决定 WEB 是否暴露;可空=不暴露 WEB)
     */
    public SearchToolCallbacks(KnowledgeSearchTool kbTool, WebSearchRouter webRouter,
                               List<Long> anchors, WebSearchSnapshot snapshot) {
        this.kbTool = kbTool;
        this.webRouter = webRouter;
        this.anchors = anchors == null ? List.of() : List.copyOf(anchors);
        this.snapshot = snapshot;
    }

    /** 便捷构造:无锚点、无 WEB 快照(仅暴露 KB)。 */
    public SearchToolCallbacks(KnowledgeSearchTool kbTool, WebSearchRouter webRouter) {
        this(kbTool, webRouter, List.of(), null);
    }

    /**
     * 按名产出 callbacks。
     *
     * <ul>
     *   <li>{@code KB} → {@value #NAME_KB}(仅当 {@link SearchTool#available()} 为真);</li>
     *   <li>{@code TAVILY}/{@code SEARXNG} → {@value #NAME_WEB}(同一 router 调用,provider 顺序由快照决定;
     *       仅当 WEB 门控放行且至少一个 provider 已配置时暴露,与 {@code applySettingGates} 的 WEB 剔除语义一致);</li>
     *   <li>未知名 / 重复名忽略;不可用的工具**不暴露**。</li>
     * </ul>
     *
     * @param names 工具名列表(大小写不敏感);null/空返回空数组
     * @return Spring AI ToolCallback 数组(无可用工具时为空数组,不返回 null)
     */
    public ToolCallback[] forTools(List<String> names) {
        if (names == null || names.isEmpty()) return new ToolCallback[0];
        List<Object> adapters = new ArrayList<>();
        boolean kbAdded = false;
        boolean webAdded = false;
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toUpperCase(Locale.ROOT);
            if (TOOL_KB.equals(name)) {
                if (!kbAdded && kbTool != null && kbTool.available()) {
                    adapters.add(new KbAdapter(kbTool, anchors));
                    kbAdded = true;
                }
            } else if (TOOL_TAVILY.equals(name) || TOOL_SEARXNG.equals(name) || TOOL_WEB.equals(name)) {
                if (!webAdded && webAvailable()) {
                    adapters.add(new WebAdapter(webRouter, snapshot));
                    webAdded = true;
                }
            }
            // 未知工具名直接忽略(调用方已按 applySettingGates 过滤,此处再兜底)
        }
        if (adapters.isEmpty()) return new ToolCallback[0];
        return ToolCallbacks.from(adapters.toArray());
    }

    /**
     * WEB 是否可暴露:开关放行 + 至少一个 provider 配置就绪。
     * 对齐 {@code applySettingGates}(webAllowed=false 剔除 WEB);provider 未配置时暴露一个必然
     * UNCONFIGURED 的工具无意义,故一并门控。
     */
    private boolean webAvailable() {
        if (webRouter == null || snapshot == null || !snapshot.webAllowed()) return false;
        try {
            return webRouter.configured(WebProvider.TAVILY) || webRouter.configured(WebProvider.SEARXNG);
        } catch (Exception e) {
            log.warn("web_search 可用性判定异常,按不可用处理: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** KB 工具适配器(委托 {@link KnowledgeSearchTool#search(String, int, List)})。 */
    static final class KbAdapter {

        private final KnowledgeSearchTool kbTool;
        private final List<Long> anchors;

        KbAdapter(KnowledgeSearchTool kbTool, List<Long> anchors) {
            this.kbTool = kbTool;
            this.anchors = anchors;
        }

        @Tool(name = NAME_KB,
              description = "检索本地知识库(车型数据、通用知识、官方新闻),返回标题、摘要与来源信息。")
        public String kbSearch(
                @ToolParam(description = "检索查询串") String query,
                @ToolParam(required = false, description = "返回条数上限,默认 8") Integer maxResults) {
            try {
                int n = maxResults == null || maxResults <= 0 ? DEFAULT_KB_MAX : maxResults;
                List<SearchTool.SearchHit> hits = kbTool.search(query, n, anchors);
                return render(hits);
            } catch (Exception e) {
                // 不含异常原文(与 SearchTool 绝不抛出约定一致);仅类型化日志
                log.warn("kb_search 工具调用失败: {}", e.getClass().getSimpleName());
                return "知识库检索暂不可用,无相关结果。";
            }
        }
    }

    /** WEB 工具适配器(委托 {@link WebSearchRouter#search},保留策略路由/治理/sourceId)。 */
    static final class WebAdapter {

        private final WebSearchRouter webRouter;
        private final WebSearchSnapshot snapshot;

        WebAdapter(WebSearchRouter webRouter, WebSearchSnapshot snapshot) {
            this.webRouter = webRouter;
            this.snapshot = snapshot;
        }

        @Tool(name = NAME_WEB,
              description = "检索外部网页(按策略路由 Tavily/SearxNG),返回带稳定 sourceId、URL、来源 provider 的结果,"
                          + "事实引用必须使用返回的 sourceId。")
        public String webSearch(
                @ToolParam(description = "检索查询串") String query,
                @ToolParam(required = false, description = "返回条数上限,默认 5") Integer maxResults) {
            try {
                int n = maxResults == null || maxResults <= 0 ? DEFAULT_WEB_MAX : maxResults;
                WebSearchOutcome outcome = webRouter.search(query, n, snapshot);
                List<SearchTool.SearchHit> hits = new ArrayList<>();
                for (WebHit wh : outcome.hits()) hits.add(wh.toSearchHit());
                return render(hits);
            } catch (Exception e) {
                // 异常文本可能含密钥/URL,不返回;仅类型化日志
                log.warn("web_search 工具调用失败: {}", e.getClass().getSimpleName());
                return "外部搜索暂不可用,无相关结果。";
            }
        }
    }

    /**
     * 命中字符串化(纯文本,供模型引用):保留 type/sourceId/provider/url/model/title/snippet。
     * 无命中返回中性提示串(不返回 null)。
     */
    private static String render(List<SearchTool.SearchHit> hits) {
        if (hits == null || hits.isEmpty()) return "无检索结果。";
        StringBuilder sb = new StringBuilder();
        for (SearchTool.SearchHit h : hits) {
            if (h == null) continue;
            sb.append("- [").append(h.type() == null ? "" : h.type()).append(']');
            appendIfPresent(sb, " sourceId=", h.sourceId());
            appendIfPresent(sb, " provider=", h.provider());
            appendIfPresent(sb, " url=", h.url());
            appendIfPresent(sb, " model=", h.modelName());
            sb.append(" | ").append(h.title() == null ? "" : h.title());
            if (h.snippet() != null && !h.snippet().isBlank()) sb.append(" : ").append(h.snippet());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String prefix, String value) {
        if (value != null && !value.isBlank()) sb.append(prefix).append(value);
    }
}
