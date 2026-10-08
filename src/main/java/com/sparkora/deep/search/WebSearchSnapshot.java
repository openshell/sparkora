package com.sparkora.deep.search;

import java.util.List;

/**
 * 一次研究的搜索策略与开关快照(09-25-brief-web-search R6)。
 *
 * <p>在 {@code DeepResearchService.run()} 启动时解析一次(全局配置层,非项目级/用户级),
 * 之后同一批次的全部子代理共用同一快照;研究启动后的全局设置变化不改变已启动批次。
 *
 * <p>10-04-web-fanout-merge B 增量:{@code strategy}/{@code primaryProviders}/{@code denyDomains}/{@code allowDomains}
 * 由部署级配置解析一次(运行时设置不放开),供 {@link WebSearchRouter} 的 PRIMARY_FANOUT 路由与 SearXNG 质量门使用。
 * 保留 4 参构造器/4 参 {@code of} 兼容既有调用方(默认 {@code FIRST_HIT},零回归)。
 *
 * <p>10-04 C 增量:{@code batch} 为批次上下文(三级预算/批次内缓存/跨轮去重),随批次创建与释放;
 * 父设计 §4.3 明确「挂在快照/批次上下文」,故作为快照的可选分量随批次共享。默认 {@code null}
 * (既有调用方/测试零回归:无预算/缓存/跨轮去重)。
 *
 * @param order          生效 provider 顺序
 * @param webAllowed     外部搜索是否放行(部署级 SEARCH_WEB_ENABLED &amp;&amp; 运行时 webSearchEnabled)
 * @param briefId        研究批次 brief id(仅观测,便于日志关联)
 * @param maxResults     单 provider 返回条数上限(治理截断用)
 * @param strategy       搜索策略(默认 {@link SearchStrategy#FIRST_HIT})
 * @param primaryProviders PRIMARY_FANOUT 的 primary 组 provider 集合(保持 order 外的声明序,实际取 order ∩)
 * @param denyDomains    SearXNG 质量门域名黑名单
 * @param allowDomains   SearXNG 质量门域名白名单(命中者跳过黑名单/URL 类型过滤)
 * @param batch          批次上下文(三级预算/批次内缓存/跨轮去重;可空=无批次治理,零回归)
 */
public record WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults,
                                SearchStrategy strategy, List<WebProvider> primaryProviders,
                                List<String> denyDomains, List<String> allowDomains,
                                WebBatchContext batch) {

    /** 规范化:order 非空回退默认;集合字段不可变。 */
    public WebSearchSnapshot {
        if (order == null) order = WebProviderOrder.defaults();
        strategy = strategy == null ? SearchStrategy.FIRST_HIT : strategy;
        primaryProviders = primaryProviders == null ? List.of() : List.copyOf(primaryProviders);
        denyDomains = denyDomains == null ? List.of() : List.copyOf(denyDomains);
        allowDomains = allowDomains == null ? List.of() : List.copyOf(allowDomains);
    }

    /**
     * 兼容构造器(4 参):默认 FIRST_HIT 且无质量门配置——既有调用方(测试/旧链路)逐位等价现状。
     */
    public WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults) {
        this(order, webAllowed, briefId, maxResults, SearchStrategy.FIRST_HIT, List.of(), List.of(), List.of(), null);
    }

    /**
     * 兼容构造器(8 参,10-04 B):batch=null(无批次治理,零回归)。
     */
    public WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults,
                             SearchStrategy strategy, List<WebProvider> primaryProviders,
                             List<String> denyDomains, List<String> allowDomains) {
        this(order, webAllowed, briefId, maxResults, strategy, primaryProviders, denyDomains, allowDomains, null);
    }

    /**
     * 语义策略标签:PRIMARY_FANOUT 策略开启时回落 {@link WebProviderOrder#PRIMARY_FANOUT};
     * 否则沿用 order 的三值标签(TAVILY_FIRST / SEARXNG_FIRST;含 SERPER → PRIMARY_FANOUT)。
     */
    public String strategyLabel() {
        return strategy == SearchStrategy.PRIMARY_FANOUT
                ? WebProviderOrder.PRIMARY_FANOUT
                : order.strategyLabel();
    }

    /** 规范化 provider 串。 */
    public String strategyRaw() {
        return order.raw();
    }

    /** 构造快照(order 为 null 时回退默认;策略 FIRST_HIT)。 */
    public static WebSearchSnapshot of(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults) {
        return new WebSearchSnapshot(order, webAllowed, briefId, maxResults);
    }

    /** 全参构造(10-04 B:含策略与质量门配置;batch=null)。 */
    public static WebSearchSnapshot of(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults,
                                       SearchStrategy strategy, List<WebProvider> primaryProviders,
                                       List<String> denyDomains, List<String> allowDomains) {
        return new WebSearchSnapshot(order, webAllowed, briefId, maxResults, strategy, primaryProviders,
                denyDomains, allowDomains);
    }

    /** 返回携带批次上下文的快照副本(10-04 C);{@code batch} 为 null 或已相同时返回自身。 */
    public WebSearchSnapshot withBatch(WebBatchContext batch) {
        if (batch == null || batch == this.batch) return this;
        return new WebSearchSnapshot(order, webAllowed, briefId, maxResults, strategy, primaryProviders,
                denyDomains, allowDomains, batch);
    }

    /**
     * 返回替换搜索策略的快照副本(10-04 C:R2 补检索强制 {@link SearchStrategy#PRIMARY_FANOUT} 多源交叉),
     * 其余分量(含 {@code batch})原样保留;策略相同则返回自身。
     */
    public WebSearchSnapshot withStrategy(SearchStrategy newStrategy) {
        SearchStrategy s = newStrategy == null ? SearchStrategy.FIRST_HIT : newStrategy;
        if (s == this.strategy) return this;
        return new WebSearchSnapshot(order, webAllowed, briefId, maxResults, s, primaryProviders,
                denyDomains, allowDomains, batch);
    }

    /** 有效 provider 顺序(不可变)。 */
    public List<WebProvider> providers() {
        return order.providers();
    }
}
