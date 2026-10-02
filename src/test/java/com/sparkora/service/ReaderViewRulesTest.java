package com.sparkora.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10-02-fix-meta-leak-in-article-body R1/R2:{@link ReaderViewRules} 单测(prompt 侧共用契约)。
 *
 * <p>核心契约(AC1):{@code suggestion}(写给作者的祈使句)绝不进 prompt,只有陈述性 {@code claim} 进禁写断言块;
 * 畸形/非数组/全无 claim 一律<b>不注入</b>(不按原文兜底,否则祈使句随畸形数据回流)。
 */
class ReaderViewRulesTest {

    private static final String HEADER = "【禁止写入正文的断言】";

    /** 线上真实样本 brief 76 第 3 条:suggestion 是泄漏源,claim 是唯一可投内容。 */
    private static final String BRIEF76 = "[{\"claim\":\"手册未提供比亚迪2026年度销量目标，也无完成进度数据\","
            + "\"riskLevel\":\"high\","
            + "\"suggestion\":\"此表述必须删除或改为「手册未披露年度目标，完成率无法计算」\"}]";

    // ==================== R1:禁写断言块 ====================

    @Test
    void claim进块_suggestion祈使句绝不进块() {
        String block = ReaderViewRules.forbiddenClaimsBlock(BRIEF76);

        assertNotNull(block);
        assertTrue(block.startsWith(HEADER), "块头必须存在");
        assertTrue(block.contains("- 手册未提供比亚迪2026年度销量目标，也无完成进度数据"), "claim 以清单项注入");
        assertFalse(block.contains("此表述必须删除或改为"), "suggestion 祈使句不得进块");
        assertFalse(block.contains("完成率无法计算"), "suggestion 原文不得进块");
        assertFalse(block.contains("riskLevel"), "不得按原文注入整个 JSON");
    }

    @Test
    void 不可用输入一律不注入() {
        for (String bad : new String[]{null, "", "   ", "[]", "{}", "[不是合法JSON", "{\"claim\":\"风险Y\"}",
                "null", "[{\"riskLevel\":\"high\"}]", "[{\"claim\":\"  \"}]", "[{\"claim\":123}]",
                "[{\"claim\":[\"嵌套数组\"]}]", "[\"字符串项\"]", "[{}]"}) {
            assertNull(ReaderViewRules.forbiddenClaimsBlock(bad), "不可用输入不得注入禁写块: " + bad);
        }
    }

    @Test
    void 混合数组_只取合法claim且按原序() {
        String json = "[{\"claim\":\"断言甲\"},{\"riskLevel\":\"high\"},{\"claim\":\"断言乙\"},\"x\",{}]";

        List<String> claims = ReaderViewRules.factRiskClaims(json);

        assertNotNull(claims);
        assertEquals(List.of("断言甲", "断言乙"), claims);
    }

    /** 多行/多空白 claim 归一为单空格:否则「- 」清单会出现无前缀续行,prompt 结构被破坏。 */
    @Test
    void 多行claim_归一为单行不破坏清单结构() {
        String block = ReaderViewRules.forbiddenClaimsBlock(
                "[{\"claim\":\"第一行断言\\n第二行断言\\t第三行\"}]");

        assertNotNull(block);
        String[] lines = block.split("\n");
        assertEquals(2, lines.length, "块头 + 单个清单项,不得出现无前缀续行");
        assertEquals("- 第一行断言 第二行断言 第三行", lines[1]);
    }

    /** 超长 claim 截断,防止单条断言挤爆 prompt 预算。 */
    @Test
    void 超长claim_截断到上限() {
        String block = ReaderViewRules.forbiddenClaimsBlock("[{\"claim\":\"" + "x".repeat(500) + "\"}]");

        assertNotNull(block);
        String item = block.split("\n")[1];
        assertTrue(item.length() <= 202, "清单项应被截断(含 \"- \" 前缀): " + item.length());
    }

    // ==================== R2:读者视角铁律 ====================

    @Test
    void 读者视角铁律_含黑名单与禁止解释数据缺失() {
        String rules = ReaderViewRules.READER_RULES;

        assertTrue(rules.contains("读者视角铁律"));
        assertTrue(rules.contains("你只写给读者看"));
        for (String word : new String[]{"手册", "事实手册", "简报", "大纲", "事实风险", "未收录", "未提供",
                "无法计算", "待核实", "不应作为结论", "据手册", "知识库未覆盖"}) {
            assertTrue(rules.contains(word), "黑名单应含内部话术: " + word);
        }
        assertTrue(rules.contains("禁止在正文中解释"), "应显式禁止解释数据缺失");
        assertTrue(rules.contains("资料未覆盖的论点直接不写"), "应给出替代行为指令");
    }

    /** 铁律与禁写块均为无状态常量:多次调用结果一致(纯静态范式,同 LayoutRules)。 */
    @Test
    void 无状态_多次调用结果一致() {
        assertEquals(ReaderViewRules.forbiddenClaimsBlock(BRIEF76), ReaderViewRules.forbiddenClaimsBlock(BRIEF76));
        assertEquals(ReaderViewRules.READER_RULES, ReaderViewRules.READER_RULES);
    }
}
