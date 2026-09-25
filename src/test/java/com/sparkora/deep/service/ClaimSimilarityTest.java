package com.sparkora.deep.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClaimSimilarity 纯函数单测:数值签名硬前提、阈值判定、误合并防护(09-25-fact-claim-merge)。
 */
class ClaimSimilarityTest {

    // ---------- 规范化 ----------

    @Test
    void 规范化_去标点空白与标记_仅留中英文数字() {
        assertEquals("比亚迪第2000座it之家", ClaimSimilarity.normalize("比亚迪 第 2000 座 - IT之家"));
        assertEquals("hello世界123", ClaimSimilarity.normalize("**Hello**, 世界123!"));
        assertEquals("", ClaimSimilarity.normalize(null));
    }

    // ---------- 数值签名 ----------

    @Test
    void 数值签名_千分位与万_归一为同一值() {
        assertEquals(ClaimSimilarity.numberValues("239900"), ClaimSimilarity.numberValues("239,900"));
        // 20万 = 200000
        assertEquals(List.of("200000"), ClaimSimilarity.numberValues("20万"));
        assertEquals(List.of("200000"), ClaimSimilarity.numberValues("200000"));
    }

    @Test
    void 数值签名_亿换算与稳定去重排序() {
        assertEquals(List.of("100000000"), ClaimSimilarity.numberValues("1亿"));
        // 去重 + 按数值升序
        assertEquals(List.of("1", "2000"), ClaimSimilarity.numberValues("第2000座 第2000座 第1座"));
    }

    @Test
    void 数值签名_无数字为空集合_解析异常不抛出() {
        assertTrue(ClaimSimilarity.numberValues("无任何数字").isEmpty());
        assertTrue(ClaimSimilarity.numberValues((String[]) null).isEmpty());
    }

    // ---------- 相似度 ----------

    @Test
    void 相似度_同义近义高_不同语义低() {
        double high = ClaimSimilarity.similarity(
                "比亚迪第2000座高速闪充站已正式落成", "比亚迪第2000座闪充高速站正式落成");
        assertTrue(high >= 0.45, "近义 claim 相似度应达阈值: " + high);

        double low = ClaimSimilarity.similarity("海狮08EV起售价200000", "海狮08EV续航700km");
        assertTrue(low < 0.45, "语义不同应低于阈值: " + low);
    }

    @Test
    void 相似度_过短返回0() {
        assertEquals(0.0, ClaimSimilarity.similarity("abc", "abc"));
        assertEquals(0.0, ClaimSimilarity.similarity("", "比亚迪第2000座"));
    }

    // ---------- sameClaim 硬前提 ----------

    @Test
    void sameClaim_同值近义_合并() {
        assertTrue(ClaimSimilarity.sameClaim(
                "比亚迪第2000座高速闪充站已正式落成", "",
                "比亚迪第2000座闪充高速站正式落成", ""));
    }

    @Test
    void sameClaim_数值冲突_绝不合并() {
        assertFalse(ClaimSimilarity.sameClaim(
                "比亚迪第2000座高速闪充站落成", "", "比亚迪第1500座高速闪充站落成", ""));
    }

    /** 数值冲突回归:normalize 去掉了小数点/千分位,「1.5万」与「15万」等会规范化成同一串,不得因此误并。 */
    @Test
    void sameClaim_规范化后相同但小数点致数值冲突_绝不合并() {
        assertEquals(ClaimSimilarity.normalize("海狮08EV售价1.5万"),
                ClaimSimilarity.normalize("海狮08EV售价15万"), "前提:规范化后确实相同");
        assertFalse(ClaimSimilarity.sameClaim(
                "海狮08EV售价1.5万", "", "海狮08EV售价15万", ""), "1.5万 vs 15万 数值冲突不得合并");
        assertFalse(ClaimSimilarity.sameClaim(
                "海狮08EV轴距2.9米", "", "海狮08EV轴距29米", ""), "2.9米 vs 29米 数值冲突不得合并");
    }

    @Test
    void sameClaim_一侧有数字一侧无数值_不合并() {
        assertFalse(ClaimSimilarity.sameClaim(
                "海狮08EV起售价239900", "239900", "海狮08EV起售价待公布", ""));
    }

    @Test
    void sameClaim_无数值定性_高阈值下才合并() {
        // 措辞级差异(定性,阈值 0.70)
        assertTrue(ClaimSimilarity.sameClaim(
                "比亚迪刀片电池安全性能优异", "", "比亚迪刀片电池安全性优异", ""));
        // 共享部分文字但语义不同 → 不达 0.70
        assertFalse(ClaimSimilarity.sameClaim(
                "比亚迪销量持续增长", "", "比亚迪利润持续增长", ""));
    }

    @Test
    void sameClaim_万与纯数字视为同一数值_合并() {
        assertTrue(ClaimSimilarity.sameClaim(
                "海狮08EV起售价20万", "", "海狮08EV起售价200000元", ""));
    }

    @Test
    void sameClaim_空claim_不合并() {
        assertFalse(ClaimSimilarity.sameClaim("", "", "比亚迪第2000座落成", ""));
    }

    @Test
    void sameClaim_完全相同短claim_保持旧精确匹配() {
        // 旧行为:精确相等的 claim 必合并;规范化后相同(如带标点/大小写差异)也应合并
        assertTrue(ClaimSimilarity.sameClaim("起售价", "239900", "起售价", "239900"));
        assertTrue(ClaimSimilarity.sameClaim("海狮08EV起售价", "", "海狮08EV 起售价！", ""));
    }
}
