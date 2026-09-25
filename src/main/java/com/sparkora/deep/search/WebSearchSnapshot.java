package com.sparkora.deep.search;

import java.util.List;

/**
 * 一次研究的搜索策略与开关快照(09-25-brief-web-search R6)。
 *
 * <p>在 {@code DeepResearchService.run()} 启动时解析一次(全局配置层,非项目级/用户级),
 * 之后同一批次的全部子代理共用同一快照;研究启动后的全局设置变化不改变已启动批次。
 *
 * @param order         生效 provider 顺序
 * @param webAllowed    外部搜索是否放行(部署级 SEARCH_WEB_ENABLED && 运行时 webSearchEnabled)
 * @param briefId       研究批次 brief id(仅观测,便于日志关联)
 * @param maxResults    单 provider 返回条数上限(治理截断用)
 */
public record WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults) {

    /** 语义策略标签(TAVILY_FIRST / SEARXNG_FIRST)。 */
    public String strategyLabel() {
        return order.strategyLabel();
    }

    /** 规范化 provider 串。 */
    public String strategyRaw() {
        return order.raw();
    }

    /** 构造快照(order 为 null 时回退默认)。 */
    public static WebSearchSnapshot of(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults) {
        WebProviderOrder o = order == null ? WebProviderOrder.defaults() : order;
        return new WebSearchSnapshot(o, webAllowed, briefId, maxResults);
    }

    /** 有效 provider 顺序(不可变)。 */
    public List<WebProvider> providers() {
        return order.providers();
    }
}
