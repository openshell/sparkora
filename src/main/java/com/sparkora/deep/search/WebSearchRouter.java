package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.tool.SearchTool;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.SerperSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 外部搜索路由组件(09-25-brief-web-search R1/R2/R5;10-04-serper-provider 注册 SERPER;
 * 10-04-web-fanout-merge B 增 PRIMARY_FANOUT 多源并行聚合)。
 *
 * <p>职责:
 * <ul>
 *   <li>{@link SearchStrategy#FIRST_HIT}(默认):按快照中的有效策略顺序逐个尝试 provider,
 *       首个产出「有效命中」即采信并停止;provider 未配置 → 跳过(UNCONFIGURED);异常/超时/空结果/
 *       结果全部无有效 URL → 记录降级原因后尝试下一个。付费 provider 不会无条件重复调用。</li>
 *   <li>{@link SearchStrategy#PRIMARY_FANOUT}:primary 组(配置集合 ∩ order)用虚拟线程并行调用,
 *       各 provider 独立治理后由 {@link WebResultNormalizer#merge} 跨源合并/统一分配 sourceId;
 *       primary 全空时才按 FIRST_HIT 逻辑对 fallback 组兜底。</li>
 * </ul>
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
    /** 10-04 C:计量源(Tavily/Serper)预算耗尽,跳过本次调用(已获证据照常入册)。 */
    public static final String REASON_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    private final Map<WebProvider, SearchTool> tools = new EnumMap<>(WebProvider.class);

    public WebSearchRouter(TavilySearchTool tavilyTool, SearxngSearchTool searxngTool,
                           SerperSearchTool serperTool) {
        tools.put(WebProvider.TAVILY, tavilyTool);
        tools.put(WebProvider.SEARXNG, searxngTool);
        tools.put(WebProvider.SERPER, serperTool);
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
        return searchInternal(query, maxResults, snapshot, null, null);
    }

    /**
     * 按策略搜索(10-04 C:带批次上下文——计量源预算 + 批次内缓存 + 跨轮去重)。
     *
     * @param batch 研究批次上下文(可空;空时等价旧行为,零回归)
     */
    public WebSearchOutcome search(String query, int maxResults, WebSearchSnapshot snapshot, WebBatchContext batch) {
        return searchInternal(query, maxResults, snapshot, null, batch);
    }

    /**
     * 按垂直搜索(10-04-serper-provider A-R3):{@code vertical=news} 走时效垂直(Serper {@code /news})。
     *
     * <p>与 {@link #search} 同语义,仅把工具调用换为 {@link SearchTool#searchVertical};
     * 用于 SubAgentRunner 的时效题路由。不支持垂直的工具经默认实现回落 {@code search},行为等价。
     */
    public WebSearchOutcome searchVertical(String query, int maxResults, WebSearchSnapshot snapshot, String vertical) {
        return searchInternal(query, maxResults, snapshot, vertical, null);
    }

    /** 按垂直搜索(10-04 C:带批次上下文)。 */
    public WebSearchOutcome searchVertical(String query, int maxResults, WebSearchSnapshot snapshot, String vertical,
                                           WebBatchContext batch) {
        return searchInternal(query, maxResults, snapshot, vertical, batch);
    }

    /**
     * 搜索核心。
     *
     * @param vertical {@code null} 表示走既有 {@link SearchTool#search}(零回归路径);
     *                 非空时走 {@link SearchTool#searchVertical}(news 垂直)
     * @param batch    批次上下文(计量预算/缓存/跨轮去重;可空)
     */
    private WebSearchOutcome searchInternal(String query, int maxResults, WebSearchSnapshot snapshot, String vertical,
                                            WebBatchContext batch) {
        if (snapshot == null || !snapshot.webAllowed()) {
            // R4:任一部署级/运行时 WEB 开关关闭时不发起任何请求
            return WebSearchOutcome.empty(snapshot == null ? null : snapshot.order(), REASON_DISABLED);
        }
        List<WebProvider> order = snapshot.providers();
        if (snapshot.strategy() != SearchStrategy.PRIMARY_FANOUT) {
            return firstHit(query, maxResults, snapshot, vertical, order, null, batch);
        }
        return primaryFanout(query, maxResults, snapshot, vertical, order, batch);
    }

    /**
     * FIRST_HIT(单源短路):按给定 provider 顺序逐个尝试,首个有效命中即返回。
     * 代码与 10-04 B 之前逐位等价(仅参数化 provider 子集供 fanout 的 fallback 复用)。
     *
     * @param providers 本次尝试的 provider 顺序
     * @param prefix    已有 attempts(fanout fallback 场景);null 时新建
     */
    private WebSearchOutcome firstHit(String query, int maxResults, WebSearchSnapshot snapshot, String vertical,
                                      List<WebProvider> providers, List<WebSearchOutcome.Attempt> prefix,
                                      WebBatchContext batch) {
        List<WebSearchOutcome.Attempt> attempts = prefix == null ? new ArrayList<>() : prefix;
        for (WebProvider p : providers) {
            SearchTool tool = tools.get(p);
            if (tool == null || !tool.available()) {
                // R5:未配置跳过(available() 仅判配置就绪,不含失败闩锁)
                attempts.add(new WebSearchOutcome.Attempt(p, 0, 0L, REASON_UNCONFIGURED, false));
                continue;
            }
            // 10-04 C-R5:批次内缓存命中优先(不消耗预算);未命中才申请预算
            List<SearchTool.SearchHit> cached = batch == null ? null
                    : batch.cache().get(p, query, vertical, maxResults);
            if (cached == null) {
                // 10-04 C-R3:计量源预算申请失败 → 跳过并记 BUDGET_EXHAUSTED(已获证据照常入册)
                if (batch != null && !batch.budget().tryAcquire(p)) {
                    attempts.add(new WebSearchOutcome.Attempt(p, 0, 0L, REASON_BUDGET_EXHAUSTED, false));
                    log.info("WEB 搜索预算耗尽,跳过 provider briefId={} provider={}", snapshot.briefId(), p);
                    continue;
                }
            }
            long began = System.currentTimeMillis();
            List<SearchTool.SearchHit> raw;
            try {
                raw = cached != null ? cached
                        : (vertical == null ? tool.search(query, maxResults)
                        : tool.searchVertical(query, vertical, maxResults));
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - began;
                // R12:异常文本可能含密钥,仅记类型化原因,不回传原始异常文本
                log.warn("WEB 搜索 provider 异常降级 briefId={} provider={} latencyMs={}",
                        snapshot.briefId(), p, cost);
                attempts.add(new WebSearchOutcome.Attempt(p, 0, cost, REASON_ERROR, false, 0, tool.lastUsedEndpoint()));
                continue;
            }
            if (batch != null && cached == null) batch.cache().put(p, query, vertical, maxResults, raw);
            long cost = System.currentTimeMillis() - began;
            int rawCount = raw == null ? 0 : raw.size();
            List<WebHit> hits = WebResultNormalizer.normalize(raw, maxResults);
            // 10-04 C-R4:批次存在时经 merge 统一分配 sourceId;Round 1 只收集已见 URL(Round 2 起启用去重,
            // 避免并行子代理互相剔除证据)。batch==null 时逐位等价旧行为。
            if (batch != null) {
                Set<String> seen = batch.dedupSeenUrls();
                hits = WebResultNormalizer.merge(List.of(p), Map.of(p, hits), maxResults, seen,
                        batch::addDeduped);
                if (!batch.dedupEnabled()) batch.markSeen(hits);
            }
            if (!hits.isEmpty()) {
                attempts.add(new WebSearchOutcome.Attempt(p, hits.size(), cost, null, true, 0, tool.lastUsedEndpoint()));
                log.info("WEB 搜索命中 briefId={} strategy={} provider={} resultCount={} latencyMs={} query={}",
                        snapshot.briefId(), snapshot.strategyLabel(), p, hits.size(), cost, truncate(query));
                return new WebSearchOutcome(hits, p, attempts);
            }
            // 空结果 与 「有结果但全部无有效 URL」 区分记录,便于定位上游异常
            String reason = rawCount == 0 ? REASON_EMPTY : REASON_INVALID_URL;
            attempts.add(new WebSearchOutcome.Attempt(p, 0, cost, reason, false, 0, tool.lastUsedEndpoint()));
            log.info("WEB 搜索未产出有效命中,按策略降级 briefId={} strategy={} provider={} reason={} latencyMs={}",
                    snapshot.briefId(), snapshot.strategyLabel(), p, reason, cost);
        }
        if (providers.isEmpty()) {
            log.warn("WEB 搜索策略为空,无 provider 可尝试 briefId={}", snapshot.briefId());
        }
        return new WebSearchOutcome(List.of(), null, attempts);
    }

    /**
     * PRIMARY_FANOUT(10-04 B-R2/B-R3):primary 组并行调用 → 跨源合并 → 全空时 fallback 组短路兜底。
     */
    private WebSearchOutcome primaryFanout(String query, int maxResults, WebSearchSnapshot snapshot,
                                           String vertical, List<WebProvider> order, WebBatchContext batch) {
        List<WebProvider> usable = new ArrayList<>();
        for (WebProvider p : order) {
            SearchTool tool = tools.get(p);
            if (tool != null && tool.available()) usable.add(p);
        }
        List<WebProvider> primary = new ArrayList<>();
        for (WebProvider p : order) {
            if (usable.contains(p) && snapshot.primaryProviders().contains(p)) primary.add(p);
        }
        if (primary.isEmpty()) {
            // primary 为空(未配置/配置集合与 order 无交集)→ 整体回落 FIRST_HIT(SearxNG-only 逐位不变)
            return firstHit(query, maxResults, snapshot, vertical, order, null, batch);
        }

        // 并行调用 primary 组(虚拟线程,各取满 maxResults;异常/超时隔离,不回传异常文本)
        // 并发写入:用 ConcurrentHashMap 承载,合并时按 order 稳定遍历保证结果确定
        Map<WebProvider, List<WebHit>> perProvider = new java.util.concurrent.ConcurrentHashMap<>();
        Map<WebProvider, WebSearchOutcome.Attempt> primaryAttempts = new java.util.concurrent.ConcurrentHashMap<>();
        callPrimaryParallel(query, maxResults, snapshot, vertical, primary, perProvider, primaryAttempts, batch);

        // 跨源合并(去重/见证累加/order 位次排序/截断/sourceId 统一分配 + 跨轮去重)
        Set<String> seen = batch == null ? null : batch.dedupSeenUrls();
        List<WebHit> merged = WebResultNormalizer.merge(order, perProvider, maxResults, seen,
                batch == null ? null : batch::addDeduped);
        if (batch != null && !batch.dedupEnabled()) batch.markSeen(merged);
        if (!merged.isEmpty()) {
            // attempts 严格按 order 重建:primary 用实测结果(含 witnessTotal),其余未配置记 UNCONFIGURED,
            // fallback 组本轮未调用故不记录(与 FIRST_HIT「只记到首次命中为止」语义一致)。
            List<WebSearchOutcome.Attempt> attempts = new ArrayList<>();
            List<WebProvider> usedProviders = new ArrayList<>();
            for (WebProvider p : order) {
                WebSearchOutcome.Attempt a = primaryAttempts.get(p);
                if (a != null) {
                    int witness = witnessTotal(perProvider, p);
                    attempts.add(new WebSearchOutcome.Attempt(p, a.resultCount(), a.latencyMs(), a.fallbackReason(),
                            a.ok(), witness, a.usedEndpoint()));
                    if (a.ok() && !usedProviders.contains(p)) usedProviders.add(p);
                } else if (!usable.contains(p)) {
                    attempts.add(new WebSearchOutcome.Attempt(p, 0, 0L, REASON_UNCONFIGURED, false));
                }
            }
            WebProvider first = usedProviders.isEmpty() ? null : usedProviders.get(0);
            log.info("WEB 多源聚合命中 briefId={} strategy={} primary={} providers={} resultCount={} query={}",
                    snapshot.briefId(), snapshot.strategyLabel(), primary, usedProviders, merged.size(),
                    truncate(query));
            return new WebSearchOutcome(merged, first, attempts, usedProviders);
        }

        // primary 全空 → fallback 组(usable - primary)按原短路逻辑兜底
        List<WebProvider> fallback = new ArrayList<>();
        for (WebProvider p : usable) if (!primary.contains(p)) fallback.add(p);
        List<WebSearchOutcome.Attempt> prefix = new ArrayList<>();
        for (WebProvider p : order) {
            if (primaryAttempts.containsKey(p)) {
                WebSearchOutcome.Attempt a = primaryAttempts.get(p);
                prefix.add(new WebSearchOutcome.Attempt(p, a.resultCount(), a.latencyMs(), a.fallbackReason(),
                        a.ok(), 0, a.usedEndpoint()));
            } else if (!usable.contains(p)) {
                prefix.add(new WebSearchOutcome.Attempt(p, 0, 0L, REASON_UNCONFIGURED, false));
            }
        }
        WebSearchOutcome fb = firstHit(query, maxResults, snapshot, vertical, new ArrayList<>(fallback), prefix,
                batch);
        log.info("WEB 多源聚合 primary 全空,回落 fallback briefId={} primary={} fallback={} used={}",
                snapshot.briefId(), primary, fallback, fb.usedProvider());
        return new WebSearchOutcome(fb.hits(), fb.usedProvider(), fb.attempts(), fb.usedProviders());
    }

    /** 并行调用 primary 组:每个 provider 独立治理(含 SearXNG 质量门 + 缓存 + 预算),异常隔离。 */
    private void callPrimaryParallel(String query, int maxResults, WebSearchSnapshot snapshot, String vertical,
                                     List<WebProvider> primary,
                                     Map<WebProvider, List<WebHit>> perProvider,
                                     Map<WebProvider, WebSearchOutcome.Attempt> primaryAttempts,
                                     WebBatchContext batch) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (WebProvider p : primary) {
                final WebProvider provider = p;
                futures.add(pool.submit(() -> {
                    // 10-04 C-R5:缓存命中优先;未命中才申请预算
                    List<SearchTool.SearchHit> cached = batch == null ? null
                            : batch.cache().get(provider, query, vertical, maxResults);
                    if (cached == null && batch != null && !batch.budget().tryAcquire(provider)) {
                        // 预算耗尽:跳过(已获证据照常入册),不发起付费调用
                        primaryAttempts.put(provider,
                                new WebSearchOutcome.Attempt(provider, 0, 0L, REASON_BUDGET_EXHAUSTED, false, 0,
                                        tools.get(provider).lastUsedEndpoint()));
                        return;
                    }
                    long began = System.currentTimeMillis();
                    List<SearchTool.SearchHit> raw;
                    try {
                        SearchTool tool = tools.get(provider);
                        raw = cached != null ? cached
                                : (vertical == null ? tool.search(query, maxResults)
                                : tool.searchVertical(query, vertical, maxResults));
                    } catch (Exception e) {
                        long cost = System.currentTimeMillis() - began;
                        // 异常文本可能含密钥,仅记类型化原因
                        log.warn("WEB 多源聚合 provider 异常降级 briefId={} provider={} latencyMs={}",
                                snapshot.briefId(), provider, cost);
                        primaryAttempts.put(provider,
                                new WebSearchOutcome.Attempt(provider, 0, cost, REASON_ERROR, false, 0,
                                        tools.get(provider).lastUsedEndpoint()));
                        return;
                    }
                    if (batch != null && cached == null) batch.cache().put(provider, query, vertical, maxResults, raw);
                    long cost = System.currentTimeMillis() - began;
                    int rawCount = raw == null ? 0 : raw.size();
                    List<WebHit> hits = WebResultNormalizer.normalize(raw, maxResults);
                    // SearXNG 质量门(B-R2a):只影响是否进合并池,不提升独立交叉计数
                    if (provider == WebProvider.SEARXNG) {
                        hits = WebResultNormalizer.applyQualityGate(hits, snapshot.denyDomains(),
                                snapshot.allowDomains());
                    }
                    if (hits.isEmpty()) {
                        String reason = rawCount == 0 ? REASON_EMPTY : REASON_INVALID_URL;
                        primaryAttempts.put(provider,
                                new WebSearchOutcome.Attempt(provider, 0, cost, reason, false, 0,
                                        tools.get(provider).lastUsedEndpoint()));
                        return;
                    }
                    perProvider.put(provider, hits);
                    primaryAttempts.put(provider,
                            new WebSearchOutcome.Attempt(provider, hits.size(), cost, null, true, 0,
                                    tools.get(provider).lastUsedEndpoint()));
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) { /* 任务内部已隔离 */ }
            }
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * provider p 的命中中,URL 也被其他 provider 命中的条数(交叉验证观测)。
     */
    private static int witnessTotal(Map<WebProvider, List<WebHit>> perProvider, WebProvider p) {
        List<WebHit> hits = perProvider.get(p);
        if (hits == null || hits.isEmpty()) return 0;
        int count = 0;
        for (WebHit h : hits) {
            String url = WebResultNormalizer.normalizeUrl(h.url());
            if (url == null) continue;
            for (Map.Entry<WebProvider, List<WebHit>> e : perProvider.entrySet()) {
                if (e.getKey() == p) continue;
                boolean witnessed = false;
                for (WebHit other : e.getValue()) {
                    if (other != null && url.equals(WebResultNormalizer.normalizeUrl(other.url()))) {
                        witnessed = true;
                        break;
                    }
                }
                if (witnessed) { count++; break; }
            }
        }
        return count;
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
    /**
     * 按 URL 抽取正文片段(09-27-tavily-extract-kind-hypotheses R1,机制 B:search 拿摘要 + 按需 extract 补正文)。
     *
     * <p>保持工具抽象:按 provider 顺序尝试支持 {@link SearchTool#extract} 的工具,首个产出非空即采信并停止;
     * 不支持的工具默认返回空列表(零成本跳过)。未配置/异常/空 → 继续尝试后备;全部无 → 返回空列表
     * (调用方降级回摘要,绝不抛出)。provider 与密钥不落日志。
     *
     * <p>10-04 C-R7:签名保留 2 参重载(默认 order=TAVILY,SEARXNG、webAllowed=true)以兼容既有调用方;
     * 新调用方应传快照,见 {@link #extract(String, List, WebSearchSnapshot)}。
     */
    public List<SearchTool.SearchHit> extract(String query, List<String> urls) {
        // 10-04 C-R7 缺陷修复:旧实现按 WebProvider.values() 枚举声明序遍历且不受 webAllowed 约束。
        // 兼容重载以「默认 order + 放行」构造等价快照:默认 order(TAVILY,SEARXNG)下行为逐位等价,
        // 但 SERPER(无 extract 实现)已从默认遍历中移除,不再可能被误排序。
        return extract(query, urls, WebSearchSnapshot.of(
                WebProviderOrder.defaults(), true, null, 0));
    }

    /**
     * 按 URL 抽取正文(10-04 C-R7):按<b>快照 order</b> 遍历 + 尊重 {@code webAllowed}。
     *
     * <ul>
     *   <li>{@code webAllowed=false} → 不发起任何请求(修复旧实现 WEB 全局关闭仍付费 extract 的缺陷);</li>
     *   <li>按 {@code snapshot.providers()} 顺序遍历(与 search 策略序一致,而非枚举声明序);</li>
     *   <li>快照为 null 时回退默认 order 且放行(零回归)。</li>
     * </ul>
     */
    public List<SearchTool.SearchHit> extract(String query, List<String> urls, WebSearchSnapshot snapshot) {
        if (urls == null || urls.isEmpty()) return List.of();
        if (snapshot != null && !snapshot.webAllowed()) return List.of();   // C-R7:WEB 关闭时不发起付费 extract
        List<WebProvider> order = snapshot == null ? WebProviderOrder.defaults().providers() : snapshot.providers();
        for (WebProvider p : order) {
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
