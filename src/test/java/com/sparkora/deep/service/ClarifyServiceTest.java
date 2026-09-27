package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClarifyService 研究计划确定性兜底单测(09-26 R2/AC-02,纯函数级,不连库)。
 *
 * <p>验证 {@code ensureBackgroundQuestion}:信号词命中 → 补背景题并同步 toolHints;
 * 已有背景题/窄参数主题 → 不补;toolHints 非数组 → 不抛异常。
 */
class ClarifyServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 构造带 toolHints 数组的计划 map(keyQuestions 为 List<String>)。 */
    private Map<String, Object> plan(List<String> questions, ArrayNode hints) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", questions);
        p.put("toolHints", hints);
        return p;
    }

    private ArrayNode hints(String... questionToolsPairs) {
        ArrayNode arr = json.createArrayNode();
        for (String q : questionToolsPairs) {
            var h = arr.objectNode();
            h.put("question", q);
            var t = h.putArray("tools");
            t.add("KB");
            t.add("WEB");
            arr.add(h);
        }
        return arr;
    }

    @Test
    void 信号词命中且无背景题_补一条且toolHint对齐() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "续航多长")), hints("价格是多少", "续航多长"));

        ClarifyService.ensureBackgroundQuestion(p, "如何看待比亚迪宣布建成第2000座闪充站", null);

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(3, questions.size(), "应补一条背景题");
        assertTrue(questions.get(2).contains("行业背景"), "新问题应为背景/来龙去脉型: " + questions.get(2));
        ArrayNode h = (ArrayNode) p.get("toolHints");
        assertEquals(3, h.size(), "toolHints 应与 keyQuestions 1:1 同步补齐");
        assertEquals(questions.get(2), h.get(2).path("question").asText(), "toolHint 的 question 必须与新问题一致");
        assertTrue(h.get(2).path("tools").toString().contains("WEB"), "兜底补的题不应落到 KB-only 默认");
    }

    @Test
    void 已有背景型问题_幂等不重复补() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "该战略的行业背景与意义是什么")), hints("a", "b"));

        ClarifyService.ensureBackgroundQuestion(p, "比亚迪宣布新战略", null);

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "已含背景题不得重复补");
        assertEquals(2, ((ArrayNode) p.get("toolHints")).size(), "toolHints 不动");
    }

    @Test
    void 窄参数主题_未命中信号词_不补() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少", "尺寸参数如何")), hints("a", "b"));

        ClarifyService.ensureBackgroundQuestion(p, "海狮08EV 参数详解", null);

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "窄参数主题不得强制补背景题");
    }

    @Test
    void 信号词在extraInfo中也命中() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格是多少")), hints("a"));

        ClarifyService.ensureBackgroundQuestion(p, "海狮08EV", "围绕第2000座闪充站落成写一篇");

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "extraInfo 命中信号词同样触发兜底");
    }

    @Test
    void toolHints非数组_不抛异常_仍补问题() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", new ArrayList<>(List.of("价格是多少")));
        p.put("toolHints", "not-an-array");

        assertDoesNotThrow(() -> ClarifyService.ensureBackgroundQuestion(p, "比亚迪宣布建成新工厂", null));
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size(), "非数组 toolHints 时问题仍应补齐(优雅降级)");
    }

    @Test
    void keyQuestions非列表_不抛异常() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyQuestions", "not-a-list");
        assertDoesNotThrow(() -> ClarifyService.ensureBackgroundQuestion(p, "比亚迪宣布建成", null));
    }

    @Test
    void 主题空白_背景题使用通用措辞() {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格")), hints("a"));
        ClarifyService.ensureBackgroundQuestion(p, "", "比亚迪宣布落成");
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) p.get("keyQuestions");
        assertEquals(2, questions.size());
        assertTrue(questions.get(1).startsWith("该主题"), "空白主题回退通用措辞: " + questions.get(1));
    }

    /** 静态方法签名可达性(编译期契约):generatePlan 内使用 Map<String,Object>。 */
    @Test
    void 计划JSON序列化包含补题() throws Exception {
        Map<String, Object> p = plan(new ArrayList<>(List.of("价格")), hints("价格"));
        ClarifyService.ensureBackgroundQuestion(p, "比亚迪宣布落成", null);
        JsonNode node = json.readTree(json.writeValueAsString(p));
        assertEquals(2, node.path("keyQuestions").size());
        assertEquals(node.path("keyQuestions").get(1).asText(),
                node.path("toolHints").get(1).path("question").asText());
    }

    // ===== R2(09-27-brief-writing-linkage-fix):背景/来龙去脉型问题判定 =====

    @Test
    void isBackgroundQuestion_背景词表命中() {
        assertTrue(ClarifyService.isBackgroundQuestion("该车型的行业背景与意义是什么?"));
        assertTrue(ClarifyService.isBackgroundQuestion("企业战略与长期目标?"));
        assertTrue(ClarifyService.isBackgroundQuestion("发展规划与布局如何?"));
        assertTrue(ClarifyService.isBackgroundQuestion("为什么会这样?"));
        assertTrue(ClarifyService.isBackgroundQuestion("发展历程回顾"));
    }

    @Test
    void isBackgroundQuestion_主题信号词命中() {
        assertTrue(ClarifyService.isBackgroundQuestion("第2000座闪充站落成的意义?"));
        assertTrue(ClarifyService.isBackgroundQuestion("这次发布会宣布了什么?"));
        assertTrue(ClarifyService.isBackgroundQuestion("该里程碑事件的影响?"));
    }

    @Test
    void isBackgroundQuestion_参数型问题为负例() {
        assertEquals(false, ClarifyService.isBackgroundQuestion("海狮08的价格是多少?"));
        assertEquals(false, ClarifyService.isBackgroundQuestion("续航里程与充电速度?"));
        assertEquals(false, ClarifyService.isBackgroundQuestion("车身尺寸参数?"));
    }

    @Test
    void isBackgroundQuestion_空输入为false() {
        assertEquals(false, ClarifyService.isBackgroundQuestion(null));
        assertEquals(false, ClarifyService.isBackgroundQuestion(""));
        assertEquals(false, ClarifyService.isBackgroundQuestion("   "));
    }
}
