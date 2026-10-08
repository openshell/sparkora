package com.sparkora.deep.search;

import com.sparkora.deep.tool.SearchTool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 研究批次内搜索缓存(10-04-web-followup-budget C-R5)。
 *
 * <p>进程内 {@link ConcurrentHashMap},key = {@code provider | 规范化 query | vertical | maxResults},TTL 可配
 * (默认 10min)。<b>作用域严格限定单次 research 批次</b>(由批次上下文持有,随批次结束 {@link #release()} 释放)
 * ——天然不跨用户、不跨请求留数据,符合 {@code SecurityUtil} 隔离要求。
 *
 * <p><b>不做跨批次持久缓存</b>:搜索结果时效性强,跨批次命中会返回陈旧证据、反噬质量目标;且需加表 + 迁移 +
 * 失效策略,复杂度收益不匹配。批次内去重(跨轮 {@code seenUrls})已消除主要浪费源——Round 2 与 Round 1 的
 * query 高度重叠,正是本缓存主战场。
 *
 * <p>{@link #hits()} 计数供 {@code SearchMeta.cacheHit} 观测,否则「大量调用」的账单异常无法归因。
 * 本类线程安全(primary 组虚拟线程并行调用)。
 */
public final class WebSearchCache {

    private final long ttlMs;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private volatile int hits;

    /** 缓存条目(创建时刻 + 不可变命中副本)。 */
    private record Entry(long createdAt, List<SearchTool.SearchHit> hits) {
    }

    public WebSearchCache(long ttlMs) {
        this.ttlMs = ttlMs > 0 ? ttlMs : 600000;
    }

    /**
     * 取缓存命中(未命中/已过期返回 null)。
     *
     * @param provider   搜索 provider
     * @param query      查询串(内部按 trim+小写+压缩空白规范化)
     * @param vertical   垂直(null/{@code web}/{@code news})
     * @param maxResults 返回条数上限
     */
    public List<SearchTool.SearchHit> get(WebProvider provider, String query, String vertical, int maxResults) {
        String key = key(provider, query, vertical, maxResults);
        Entry e = store.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.createdAt() > ttlMs) {
            store.remove(key);
            return null;
        }
        hits++;
        return e.hits();
    }

    /** 写入缓存(命中列表不可变副本;空列表不缓存,避免把「无结果」长期钉死)。 */
    public void put(WebProvider provider, String query, String vertical, int maxResults,
                    List<SearchTool.SearchHit> rawHits) {
        if (rawHits == null || rawHits.isEmpty()) return;
        store.put(key(provider, query, vertical, maxResults),
                new Entry(System.currentTimeMillis(), List.copyOf(rawHits)));
    }

    /** 累计缓存命中次数(供 {@code SearchMeta.cacheHit} 观测)。 */
    public int hits() {
        return hits;
    }

    /** 批次结束释放全部缓存(不留跨批次数据)。 */
    public void release() {
        store.clear();
        hits = 0;
    }

    /** 当前缓存条目数(测试/观测用)。 */
    public int size() {
        return store.size();
    }

    private static String key(WebProvider provider, String query, String vertical, int maxResults) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String v = vertical == null ? "web" : vertical.trim().toLowerCase(Locale.ROOT);
        return (provider == null ? "" : provider.name()) + "|" + q + "|" + v + "|" + maxResults;
    }
}
