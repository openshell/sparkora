package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 WEB 搜索的结果与可观测元数据(09-25-brief-web-search R10)。
 *
 * <p>10-04-web-fanout-merge B-R4 增量:保留 {@code usedProvider}(首个产出命中的 provider,兼容既有前端/测试),
 * <b>新增</b> {@code usedProviders}(本轮参与采信的 provider 列表;FIRST_HIT 为单元素)。
 * 保留旧 3 参构造器(委托 {@code usedProviders=[usedProvider]}),既有调用方逐位不变。
 *
 * @param hits          治理后的命中(带稳定 sourceId;可能为空)
 * @param usedProvider  实际首个采信的 provider(无有效命中时为 null)
 * @param attempts      按策略顺序的尝试记录(含跳过/失败/降级原因)
 * @param usedProviders 本轮采信的 provider 列表(FIRST_HIT 为单元素/空;PRIMARY_FANOUT 为 primary 组)
 */
public record WebSearchOutcome(List<WebHit> hits, WebProvider usedProvider, List<Attempt> attempts,
                               List<WebProvider> usedProviders) {

    /** 兼容构造器(3 参):usedProviders 由 usedProvider 派生。 */
    public WebSearchOutcome(List<WebHit> hits, WebProvider usedProvider, List<Attempt> attempts) {
        this(hits, usedProvider, attempts,
                usedProvider == null ? List.of() : List.of(usedProvider));
    }

    /** 规范化:集合字段不可变。 */
    public WebSearchOutcome {
        usedProviders = usedProviders == null ? List.of() : List.copyOf(usedProviders);
    }

    /**
     * 单次 provider 尝试记录。
     *
     * @param provider      被尝试的 provider
     * @param resultCount   治理后有效命中数
     * @param latencyMs     耗时(毫秒)
     * @param fallbackReason 未采信原因:UNCONFIGURED/EMPTY/INVALID_URL/ERROR;成功为 null
     * @param ok            是否采信本次结果(FIRST_HIT:首个有效命中即 true 并停止后续)
     * @param witnessTotal  10-04 B-R5 增量:该 provider 命中中「已有其他 provider 见证」的条数(交叉验证观测)
     * @param usedEndpoint  10-05-tavily-endpoint-priority 增量:多端点 provider(TAVILY relay/official)
     *                      实际使用的端点 id;单端点 provider 或未知为 {@code null}(仅观测,不参与判定)
     */
    public record Attempt(WebProvider provider, int resultCount, long latencyMs, String fallbackReason,
                          boolean ok, int witnessTotal, String usedEndpoint) {

        /** 兼容构造器(6 参):usedEndpoint=null(旧调用方逐位不变)。 */
        public Attempt(WebProvider provider, int resultCount, long latencyMs, String fallbackReason,
                       boolean ok, int witnessTotal) {
            this(provider, resultCount, latencyMs, fallbackReason, ok, witnessTotal, null);
        }

        /** 兼容构造器(5 参):witnessTotal=0、usedEndpoint=null。 */
        public Attempt(WebProvider provider, int resultCount, long latencyMs, String fallbackReason, boolean ok) {
            this(provider, resultCount, latencyMs, fallbackReason, ok, 0, null);
        }
    }

    /** 无任何 provider 被尝试(开关关闭/顺序为空)。 */
    public static WebSearchOutcome empty(WebProviderOrder order, String reason) {
        List<Attempt> attempts = new ArrayList<>();
        if (order != null) {
            for (WebProvider p : order.providers()) {
                attempts.add(new Attempt(p, 0, 0L, reason, false));
            }
        }
        return new WebSearchOutcome(List.of(), null, attempts);
    }

    /** 是否有有效命中。 */
    public boolean hasHits() {
        return hits != null && !hits.isEmpty();
    }

    /** 降级原因汇总(首个非 ok attempt 的 reason;无则 null),供 notes 观测。 */
    public String fallbackReason() {
        if (attempts == null) return null;
        for (Attempt a : attempts) {
            if (!a.ok() && a.fallbackReason() != null) return a.fallbackReason();
        }
        return null;
    }
}
