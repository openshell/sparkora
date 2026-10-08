package com.sparkora.deep.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebCallBudget 三级预算单测(10-04-web-followup-budget C-R3/AC-C3)。
 *
 * <p>覆盖:per-brief 耗尽拒绝新调用并置 budgetExhausted;per-round 生效值含保护性下限
 * {@code max(配置值, maxAgents × |计量 primary|)};免费 SearXNG 不计预算。
 */
class WebCallBudgetTest {

    private static final List<WebProvider> MIXED = List.of(
            WebProvider.TAVILY, WebProvider.SEARXNG, WebProvider.SERPER);

    @Test
    void perBrief耗尽_拒绝新调用并置标记() {
        WebCallBudget b = WebCallBudget.of(12, 3, 2, 0, List.of());
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertEquals(3, b.totalCalls());
        assertFalse(b.tryAcquire(WebProvider.TAVILY), "超出 per-brief 总量 → 拒绝");
        assertTrue(b.budgetExhausted(), "拒绝后必须置 budgetExhausted");
    }

    @Test
    void perRound上限_只对计量源计数() {
        // per-round=2、total=100、无下限(maxAgents=0)
        WebCallBudget b = WebCallBudget.of(2, 100, 2, 0, List.of());
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertTrue(b.tryAcquire(WebProvider.SERPER));
        assertFalse(b.tryAcquire(WebProvider.TAVILY), "Round 1 超 per-round → 拒绝");
        assertTrue(b.budgetExhausted());
    }

    @Test
    void 免费SearXNG_不计入预算() {
        WebCallBudget b = WebCallBudget.of(1, 1, 2, 0, List.of());
        // 免费源任意次数都不计数、不拒绝
        for (int i = 0; i < 10; i++) assertTrue(b.tryAcquire(WebProvider.SEARXNG));
        assertEquals(0, b.totalCalls(), "SearXNG 不计入总量");
        assertFalse(b.budgetExhausted());
        // 计量源仍受 1 次限制
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertFalse(b.tryAcquire(WebProvider.SERPER));
    }

    @Test
    void 保护性下限_maxAgents乘计量primary() {
        // 配置 8,但 maxAgents=6 × 计量 primary(TAVILY,SERPER)=2 → 下限 12 → 生效 12
        WebCallBudget b = WebCallBudget.of(8, 20, 2, 6, MIXED);
        assertEquals(12, b.perRoundLimit(), "生效值必须取保护性下限 6×2=12");
        // Round 1 的 12 次计量源调用不得被掐死
        for (int i = 0; i < 12; i++) assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertFalse(b.budgetExhausted(), "12 次恰好用满,不应提前耗尽");
    }

    @Test
    void 保护性下限_不含免费SearXNG() {
        // primary 仅 SEARXNG(免费)→ 计量源 0 → 下限 0;per-round 取配置值
        WebCallBudget b = WebCallBudget.of(8, 20, 2, 6, List.of(WebProvider.SEARXNG));
        assertEquals(8, b.perRoundLimit(), "SearXNG 不计入下限(计量源为 0)");
    }

    @Test
    void Round2阶段_关闭perRound封顶_仅受总量约束() {
        WebCallBudget b = WebCallBudget.of(2, 20, 2, 0, List.of());
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertTrue(b.tryAcquire(WebProvider.TAVILY));
        assertFalse(b.tryAcquire(WebProvider.TAVILY), "Round 1 超 per-round");
        // 进入 Round 2 后只受 per-brief 总量约束(总量 2 已被用满,故仍拒绝;换 total 更大的快照验证)
        WebCallBudget b2 = WebCallBudget.of(2, 20, 2, 0, List.of());
        b2.tryAcquire(WebProvider.TAVILY);
        b2.tryAcquire(WebProvider.TAVILY);
        b2.enterFollowupPhase();
        assertTrue(b2.tryAcquire(WebProvider.TAVILY), "Round 2 不再受 per-round 封顶");
    }

    @Test
    void maxFollowups_目标名额上限() {
        WebCallBudget b = WebCallBudget.of(12, 20, 2, 6, MIXED);
        assertTrue(b.tryAcquireFollowupSlot());
        assertTrue(b.tryAcquireFollowupSlot());
        assertFalse(b.tryAcquireFollowupSlot(), "超过 maxFollowups=2 → 拒绝");
        assertFalse(b.budgetExhausted(), "目标名额耗尽非成本预算,不置 budgetExhausted");
    }

    @Test
    void metered判定_仅付费源() {
        assertTrue(WebCallBudget.metered(WebProvider.TAVILY));
        assertTrue(WebCallBudget.metered(WebProvider.SERPER));
        assertFalse(WebCallBudget.metered(WebProvider.SEARXNG));
        assertFalse(WebCallBudget.metered(null));
    }
}
