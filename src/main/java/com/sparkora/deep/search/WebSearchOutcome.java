package com.sparkora.deep.search;

import com.sparkora.deep.search.WebResultNormalizer.WebHit;

import java.util.List;

/**
 * 一次 WEB 搜索的结果与可观测元数据(09-25-brief-web-search R10)。
 *
 * @param hits         治理后的命中(带稳定 sourceId;可能为空)
 * @param usedProvider 实际采信的 provider(无有效命中时为 null)
 * @param attempts     按策略顺序的尝试记录(含跳过/失败/降级原因)
 */
public record WebSearchOutcome(List<WebHit> hits, WebProvider usedProvider, List<Attempt> attempts) {

    /**
     * 单次 provider 尝试记录。
     *
     * @param provider      被尝试的 provider
     * @param resultCount   治理后有效命中数
     * @param latencyMs     耗时(毫秒)
     * @param fallbackReason 未采信原因:UNCONFIGURED/EMPTY/INVALID_URL/ERROR;成功为 null
     * @param ok            是否采信本次结果(首个有效命中即 true 并停止后续)
     */
    public record Attempt(WebProvider provider, int resultCount, long latencyMs, String fallbackReason, boolean ok) {
    }

    /** 无任何 provider 被尝试(开关关闭/顺序为空)。 */
    public static WebSearchOutcome empty(WebProviderOrder order, String reason) {
        List<Attempt> attempts = new java.util.ArrayList<>();
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
