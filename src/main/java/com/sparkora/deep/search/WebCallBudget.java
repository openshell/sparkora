package com.sparkora.deep.search;

import java.util.List;
import java.util.Set;

/**
 * 研究批次调用预算值对象(10-04-web-followup-budget C-R3)。
 *
 * <p>三级预算,随 research 批次创建、随批次释放(作用域严格限定单次研究,不跨用户/请求):
 * <ul>
 *   <li><b>per-provider</b>:单次调用请求条数上限(即 {@code maxResults},不在此对象内,由调用方传入);</li>
 *   <li><b>per-round</b>:{@link #tryAcquireRound} 计量 Round 1 阶段计量源调用次数封顶;</li>
 *   <li><b>per-brief</b>:{@link #tryAcquireFollowup} 整个简报(含 Round 2)计量源调用总量封顶;</li>
 *   <li><b>maxFollowups</b>:{@link #tryAcquireFollowupSlot} Round 2 补检索目标数上限。</li>
 * </ul>
 *
 * <p><b>只计计量/限流源(付费 provider Tavily/Serper)</b>:{@link #METERED_PROVIDERS};免费本地源 SearXNG
 * 不计入预算(无额度成本,计入会变相抬升预算阈值)。故 primary 组含 SearXNG 后计量源仍为两个,
 * {@code 6 × 2 = 12} 的推导继续成立。
 *
 * <p><b>保护性下限</b>:{@link #of} 的 per-round 生效值取 {@code max(配置值, maxAgents × |计量 primary 组|)},
 * 避免用户调小 {@code DEEP_MAX_AGENTS} 或增删 provider 时再次出现「预算掐死主流程」。超限行为为
 * <b>立即停止发起新调用并置 {@link #budgetExhausted} 标记</b>,已获证据照常入册(降级优先,不报错中断)。
 *
 * <p>本类线程安全(Round 1 子代理虚拟线程并行调用;方法均 synchronized)。
 */
public final class WebCallBudget {

    /** 计入预算的计量/限流源(付费);免费本地源(SEARXNG)不计入。 */
    public static final Set<WebProvider> METERED_PROVIDERS = Set.of(WebProvider.TAVILY, WebProvider.SERPER);

    private final int perRoundLimit;
    private final int totalLimit;
    private final int maxFollowups;
    private int roundCalls;
    private int totalCalls;
    private int followups;
    private boolean exhausted;
    /** Round 2 起关闭 per-round 封顶(只受 per-brief 总量约束);由 {@link #enterFollowupPhase()} 切换。 */
    private boolean perRoundCapEnabled = true;

    private WebCallBudget(int perRoundLimit, int totalLimit, int maxFollowups) {
        this.perRoundLimit = Math.max(0, perRoundLimit);
        this.totalLimit = Math.max(0, totalLimit);
        this.maxFollowups = Math.max(0, maxFollowups);
    }

    /**
     * 创建批次预算。
     *
     * @param configuredPerRound Round 1 计量源调用次数配置值(保护性下限前的原值)
     * @param configuredTotal    整个简报计量源调用总量上限({@code maxTotalWebCalls})
     * @param maxFollowups       Round 2 目标数上限
     * @param maxAgents          研究窗口子代理数上限(保护性下限因子;负值按 0)
     * @param primaryProviders   primary 组(仅其中计量源参与下限计算)
     */
    public static WebCallBudget of(int configuredPerRound, int configuredTotal, int maxFollowups,
                                   int maxAgents, List<WebProvider> primaryProviders) {
        int metered = 0;
        if (primaryProviders != null) {
            for (WebProvider p : primaryProviders) if (METERED_PROVIDERS.contains(p)) metered++;
        }
        int floor = Math.max(0, maxAgents) * metered;
        int perRound = Math.max(configuredPerRound, floor);
        return new WebCallBudget(perRound, configuredTotal, maxFollowups);
    }

    /** provider 是否计入预算(计量/限流源)。 */
    public static boolean metered(WebProvider p) {
        return p != null && METERED_PROVIDERS.contains(p);
    }

    /** 进入 Round 2 补检索阶段:关闭 per-round 封顶,只受 per-brief 总量约束。 */
    public synchronized void enterFollowupPhase() {
        perRoundCapEnabled = false;
    }

    /**
     * 申请一次计量源调用许可(Round 1 与 Round 2 共用 {@link WebSearchRouter} 调用点)。
     *
     * @return 非计量源恒 true(不计数);否则在 per-brief 与(仅 Round 1)per-round 均有余量时计数并放行,
     *         任一超限则置 {@link #budgetExhausted()} 并返回 false(调用方跳过该 provider)
     */
    public synchronized boolean tryAcquire(WebProvider p) {
        if (!metered(p)) return true;
        if (totalCalls >= totalLimit || (perRoundCapEnabled && roundCalls >= perRoundLimit)) {
            exhausted = true;
            return false;
        }
        totalCalls++;
        roundCalls++;
        return true;
    }

    /** 兼容命名:Round 1 申请一次计量源调用许可(委托 {@link #tryAcquire})。 */
    public synchronized boolean tryAcquireRound(WebProvider p) {
        return tryAcquire(p);
    }

    /** 兼容命名:Round 2 申请一次计量源调用许可(委托 {@link #tryAcquire},进入 followup 阶段后不受 per-round 限)。 */
    public synchronized boolean tryAcquireFollowup(WebProvider p) {
        return tryAcquire(p);
    }

    /** 申请一个 Round 2 补检索目标名额(maxFollowups 上限;非成本预算,不置耗尽标记)。 */
    public synchronized boolean tryAcquireFollowupSlot() {
        if (followups >= maxFollowups) return false;
        followups++;
        return true;
    }

    /** 是否已触发过预算耗尽(任一计量申请被拒)。 */
    public synchronized boolean budgetExhausted() {
        return exhausted;
    }

    /** 已发生的计量源调用数(per-brief)。 */
    public synchronized int totalCalls() {
        return totalCalls;
    }

    /** 已发生的 Round 1 计量源调用数(per-round)。 */
    public synchronized int roundCalls() {
        return roundCalls;
    }

    /** per-round 生效上限(含保护性下限)。 */
    public int perRoundLimit() {
        return perRoundLimit;
    }

    /** per-brief 总量上限。 */
    public int totalLimit() {
        return totalLimit;
    }

    /** Round 2 目标数上限。 */
    public int maxFollowups() {
        return maxFollowups;
    }
}
