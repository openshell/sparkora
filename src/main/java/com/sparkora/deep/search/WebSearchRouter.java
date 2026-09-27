package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.tool.SearchTool;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 外部搜索路由组件(09-25-brief-web-search R1/R2/R5)。
 *
 * <p>职责:按快照中的有效策略顺序逐个尝试 provider,首个产出「有效命中」即采信并停止;
 * provider 未配置 → 跳过(UNCONFIGURED);异常/超时/空结果/结果全部无有效 URL → 记录降级原因后尝试下一个。
 * 付费 provider 不会无条件重复调用(每个 provider 每次最多调用一次)。
 *
 * <p>治理(协议校验/规范化/去重/截断/sourceId)统一委托 {@link WebResultNormalizer},先于 LLM。
 */
@Slf4j
@Component
public class WebSearchRouter {

    /** 降级原因常量(写入 notes/日志,便于定位)。 */
    public static final String REASON_DISABLED = "DISABLED";
    public static final String REASON_UNCONFIGURED = "UNCONFIGURED";
    public static final String REASON_EMPTY = "EMPTY";
    public static final String REASON_INVALID_URL = "INVALID_URL";
    public static final String REASON_ERROR = "ERROR";

    private final Map<WebProvider, SearchTool> tools = new EnumMap<>(WebProvider.class);

    public WebSearchRouter(TavilySearchTool tavilyTool, SearxngSearchTool searxngTool) {
        tools.put(WebProvider.TAVILY, tavilyTool);
        tools.put(WebProvider.SEARXNG, searxngTool);
    }

    /**
     * 按策略搜索。
     *
     * @param query      查询串(调用方已拼接主题 + 已锁定答案)
     * @param maxResults 单 provider 返回条数上限
     * @param snapshot   本次研究的策略与开关快照(启动时解析一次)
     * @return 结果与尝试元数据(永不返回 null;开关关闭时 hits 为空)
     */
    public WebSearchOutcome search(String query, int maxResults, WebSearchSnapshot snapshot) {
        if (snapshot == null || !snapshot.webAllowed()) {
            // R4:任一部署级/运行时 WEB 开关关闭时不发起任何请求
            return WebSearchOutcome.empty(snapshot == null ? null : snapshot.order(), REASON_DISABLED);
        }
        List<WebProvider> order = snapshot.providers();
        List<WebSearchOutcome.Attempt> attempts = new ArrayList<>();
        for (WebProvider p : order) {
            SearchTool tool = tools.get(p);
            if (tool == null || !tool.available()) {
                // R5:未配置跳过(available() 仅判配置就绪,不含失败闩锁)
                attempts.add(new WebSearchOutcome.Attempt(p, 0, 0L, REASON_UNCONFIGURED, false));
                continue;
            }
            long began = System.currentTimeMillis();
            List<SearchTool.SearchHit> raw;
            try {
                raw = tool.search(query, maxResults);
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - began;
                // R12:异常文本可能含密钥,仅记类型化原因,不回传原始异常文本
                log.warn("WEB 搜索 provider 异常降级 briefId={} provider={} latencyMs={}",
                        snapshot.briefId(), p, cost);
                attempts.add(new WebSearchOutcome.Attempt(p, 0, cost, REASON_ERROR, false));
                continue;
            }
            long cost = System.currentTimeMillis() - began;
            int rawCount = raw == null ? 0 : raw.size();
            List<WebHit> hits = WebResultNormalizer.normalize(raw, maxResults);
            if (!hits.isEmpty()) {
                attempts.add(new WebSearchOutcome.Attempt(p, hits.size(), cost, null, true));
                log.info("WEB 搜索命中 briefId={} strategy={} provider={} resultCount={} latencyMs={} query={}",
                        snapshot.briefId(), snapshot.strategyLabel(), p, hits.size(), cost, truncate(query));
                return new WebSearchOutcome(hits, p, attempts);
            }
            // 空结果 与 「有结果但全部无有效 URL」 区分记录,便于定位上游异常
            String reason = rawCount == 0 ? REASON_EMPTY : REASON_INVALID_URL;
            attempts.add(new WebSearchOutcome.Attempt(p, 0, cost, reason, false));
            log.info("WEB 搜索未产出有效命中,按策略降级 briefId={} strategy={} provider={} reason={} latencyMs={}",
                    snapshot.briefId(), snapshot.strategyLabel(), p, reason, cost);
        }
        if (order.isEmpty()) {
            log.warn("WEB 搜索策略为空,无 provider 可尝试 briefId={}", snapshot.briefId());
        }
        return new WebSearchOutcome(List.of(), null, attempts);
    }

    /** provider 是否已配置(供状态接口/测试复用)。 */
    public boolean configured(WebProvider p) {
        SearchTool tool = tools.get(p);
        return tool != null && tool.configured();
    }

    /**
     * 按 URL 抽取正文片段(09-27-tavily-extract-kind-hypotheses R1,机制 B:search 拿摘要 + 按需 extract 补正文)。
     *
     * <p>保持工具抽象:按 provider 顺序尝试支持 {@link SearchTool#extract} 的工具,首个产出非空即采信并停止;
     * 不支持的工具默认返回空列表(零成本跳过)。未配置/异常/空 → 继续尝试后备;全部无 → 返回空列表
     * (调用方降级回摘要,绝不抛出)。provider 与密钥不落日志。
     */
    public List<SearchTool.SearchHit> extract(String query, List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        for (WebProvider p : WebProvider.values()) {
            SearchTool tool = tools.get(p);
            if (tool == null || !tool.available()) continue;
            try {
                List<SearchTool.SearchHit> got = tool.extract(urls, query);
                if (got != null && !got.isEmpty()) return got;
            } catch (Exception e) {
                // 异常文本可能含密钥/URL,仅记类型化原因
                log.warn("WEB 正文抽取 provider 异常降级 provider={} error={}", p, e.getClass().getSimpleName());
            }
        }
        return List.of();
    }

    /** 查询串截断(日志不写全量 query,避免噪音)。 */
    private static String truncate(String q) {
        if (q == null) return "";
        return q.length() > 120 ? q.substring(0, 120) + "…" : q;
    }
}
