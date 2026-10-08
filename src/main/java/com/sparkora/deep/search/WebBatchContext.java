package com.sparkora.deep.search;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 研究批次上下文(10-04-web-followup-budget C):持有三级预算、批次内缓存与跨轮已见 URL。
 *
 * <p>一次 research 批次创建一个实例,同批次全部子代理(Round 1 并行虚拟线程)与 Round 2 补检索共享;
 * 批次结束即废弃(不跨用户、不跨请求)。<b>只承载进程内可观测/可释放状态,不落库</b>。
 *
 * <ul>
 *   <li>{@link #budget()}:计量源(Tavily/Serper)三级调用预算;免费 SearXNG 不计;</li>
 *   <li>{@link #cache()}:批次内搜索结果缓存(TTL,随批次 {@link WebSearchCache#release()} 释放);</li>
 *   <li>{@link #seenUrls()}:跨轮去重的规范化 URL 集合(Round 1 收集,Round 2 {@code merge} 消费);</li>
 *   <li>{@link #dedupedCount()}:因跨轮去重被丢弃的候选 URL 计数(供 {@code SearchMeta} 观测)。</li>
 * </ul>
 */
public final class WebBatchContext {

    private final WebCallBudget budget;
    private final WebSearchCache cache;
    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger deduped = new AtomicInteger();
    /** 跨轮去重开关:Round 1 只收集已见 URL(子代理相互独立,不得互相去重);Round 2 起开启去重。 */
    private volatile boolean dedupEnabled = false;

    public WebBatchContext(WebCallBudget budget, WebSearchCache cache) {
        this.budget = budget;
        this.cache = cache;
    }

    public WebCallBudget budget() {
        return budget;
    }

    public WebSearchCache cache() {
        return cache;
    }

    /** 跨轮已见规范化 URL 集合(可变,由 Round 1 收集、Round 2 消费与追加)。 */
    public Set<String> seenUrls() {
        return seenUrls;
    }

    /** Round 1:只收集已见 URL,不启用跨轮去重(子代理相互独立,不得互相剔除证据)。 */
    public void startCollectPhase() {
        dedupEnabled = false;
    }

    /** Round 2:开启跨轮去重(命中 Round 1 已见 URL 直接丢弃)。 */
    public void startFollowupPhase() {
        dedupEnabled = true;
    }

    /** 当前是否启用跨轮去重(供合并逻辑读取)。 */
    public boolean dedupEnabled() {
        return dedupEnabled;
    }

    /** 供合并的跨轮去重集合:未启用去重时返回 {@code null}(无去重,与旧行为等价)。 */
    public Set<String> dedupSeenUrls() {
        return dedupEnabled ? seenUrls : null;
    }

    /** 记录一批命中 URL 为「已见」(规范化后非空者);供 Round 1 收集。 */
    public void markSeen(List<WebResultNormalizer.WebHit> hits) {
        if (hits == null) return;
        for (WebResultNormalizer.WebHit h : hits) {
            if (h == null) continue;
            String u = WebResultNormalizer.normalizeUrl(h.url());
            if (u != null) seenUrls.add(u);
        }
    }

    /** 累计跨轮去重丢弃数。 */
    public void addDeduped(int n) {
        if (n > 0) deduped.addAndGet(n);
    }

    public int dedupedCount() {
        return deduped.get();
    }

    /** 释放批次资源(缓存清空);预算对象随实例一并被 GC。 */
    public void release() {
        cache.release();
        seenUrls.clear();
    }
}
