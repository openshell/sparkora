package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepResearchService.selectFollowupTargets 缺口识别单测(10-04-web-followup-budget C-R1/AC-C1)。
 *
 * <p>纯函数、零 LLM、无网络:三类规则(a 检索类 gap / b 低置信参数型 entry / c 未被回答的 keyQuestion)
 * 各自命中、聚类去重、maxFollowups 截断、无缺口返回空(不发起任何额外调用)。
 */
class SelectFollowupTargetsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode sheet(String entriesJson, String gapsJson) throws Exception {
        return JSON.readTree("{\"entries\":[" + entriesJson + "],\"gaps\":[" + gapsJson + "]}");
    }

    private static String entry(String claim, double confidence, String kind) {
        return "{\"key\":\"" + claim + "\",\"claim\":\"" + claim + "\",\"kind\":\"" + kind
                + "\",\"confidence\":" + confidence + "}";
    }

    // ===== 规则 a:检索类 gap =====

    @Test
    void 规则a_检索类gap_命中并从引号还原claim() throws Exception {
        JsonNode fs = sheet("",
                "\"已剔除无可靠来源的 WEB 事实「价格 23 万」:URL 与 sourceId W1 不匹配\"");
        List<DeepResearchService.FollowupTarget> t = DeepResearchService.selectFollowupTargets(fs, null, 2);
        assertEquals(1, t.size());
        assertEquals("GAP", t.get(0).reason());
        assertTrue(t.get(0).claim().contains("价格 23 万"), "应从「」还原被拒 claim: " + t.get(0).claim());
    }

    @Test
    void 规则a_非检索类gap_不命中() throws Exception {
        JsonNode fs = sheet("", "\"所需证据未在事实手册中找到\"");
        assertTrue(DeepResearchService.selectFollowupTargets(fs, null, 2).isEmpty());
    }

    // ===== 规则 b:低置信参数型 entry =====

    @Test
    void 规则b_低置信参数型entry_命中() throws Exception {
        JsonNode fs = sheet(entry("续航 500km", 0.4, "param"), "");
        List<DeepResearchService.FollowupTarget> t = DeepResearchService.selectFollowupTargets(fs, null, 2);
        assertEquals(1, t.size());
        assertEquals("LOW_CONFIDENCE", t.get(0).reason());
        assertEquals("续航 500km", t.get(0).claim());
    }

    @Test
    void 规则b_高置信或背景型_不命中() throws Exception {
        // 高置信不选
        assertTrue(DeepResearchService.selectFollowupTargets(
                sheet(entry("价格 23 万", 0.85, "param"), ""), null, 2).isEmpty());
        // 背景型即使低置信也不选(仅参数型)
        assertTrue(DeepResearchService.selectFollowupTargets(
                sheet(entry("行业背景如何", 0.4, "background"), ""), null, 2).isEmpty());
    }

    // ===== 规则 c:未被回答的 keyQuestion =====

    @Test
    void 规则c_keyQuestion既无entry也无gap_命中() throws Exception {
        JsonNode fs = sheet(entry("续航 500km", 0.9, "param"), "");
        List<DeepResearchService.FollowupTarget> t = DeepResearchService.selectFollowupTargets(
                fs, List.of("续航 500km", "竞品对比如何"), 2);
        // 续航已被 entry 覆盖;竞品对比未被回答 → 仅 1 条
        assertEquals(1, t.size());
        assertEquals("UNANSWERED", t.get(0).reason());
        assertTrue(t.get(0).question().contains("竞品对比"));
    }

    @Test
    void 规则c_已由entry或gap覆盖_不命中() throws Exception {
        // gap 用非检索类(避免触发规则 a),仅验证规则 c 的「既无 entry 也无 gap」判定
        JsonNode fs = sheet(entry("续航 500km", 0.9, "param"),
                "\"「价格 23 万」所需证据未在事实手册中找到\"");
        // 两个问题分别被 entry/gap 覆盖 → 空
        assertTrue(DeepResearchService.selectFollowupTargets(
                fs, List.of("续航 500km", "价格 23 万"), 2).isEmpty());
    }

    // ===== 聚类去重 =====

    @Test
    void 聚类去重_同claim不重复() throws Exception {
        // 规则 b 低置信 entry 与规则 a gap 指向同一 claim → 去重为 1
        JsonNode fs = sheet(entry("价格 23 万", 0.4, "param"),
                "\"已剔除无可靠来源的 WEB 事实「价格 23 万」:provider 与 sourceId W1 不匹配\"");
        assertEquals(1, DeepResearchService.selectFollowupTargets(fs, null, 2).size());
    }

    // ===== 截断 =====

    @Test
    void maxFollowups截断生效() throws Exception {
        JsonNode fs = sheet(entry("a 事实 111", 0.4, "param") + "," + entry("b 事实 222", 0.4, "param")
                + "," + entry("c 事实 333", 0.4, "param"), "");
        assertEquals(2, DeepResearchService.selectFollowupTargets(fs, null, 2).size());
    }

    @Test
    void 无缺口_返回空不得发起额外调用() throws Exception {
        JsonNode fs = sheet(entry("价格 23 万", 0.9, "param"), "");
        assertTrue(DeepResearchService.selectFollowupTargets(fs, List.of("价格 23 万"), 2).isEmpty());
    }

    @Test
    void maxFollowups为0或factSheet空_返回空() throws Exception {
        JsonNode fs = sheet(entry("价格 23 万", 0.4, "param"), "");
        assertTrue(DeepResearchService.selectFollowupTargets(fs, null, 0).isEmpty(), "关闭多轮 → 空");
        assertTrue(DeepResearchService.selectFollowupTargets(null, List.of("q"), 2).isEmpty());
    }
}
