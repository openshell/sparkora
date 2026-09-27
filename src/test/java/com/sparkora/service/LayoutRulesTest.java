package com.sparkora.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 09-27-shared-layout-rules R4:共享分节档位纯函数单测。
 *
 * <p>边界 null/-1/0/800/801/1800/1801/3000/3001/10000/Integer.MAX_VALUE 逐值对齐且不抛。
 */
class LayoutRulesTest {

    @Test
    void sectionSpec_边界值逐值对齐且不抛() {
        assertSpec(null, "3~5", "2~3");
        assertSpec(-1, "3~5", "2~3");
        assertSpec(0, "3~5", "2~3");
        assertSpec(800, "2~3", "2~3");
        assertSpec(801, "3~5", "2~3");
        assertSpec(1800, "3~5", "2~3");
        assertSpec(1801, "5~8", "2~3");
        assertSpec(3000, "5~8", "2~3");
        assertSpec(3001, "8~12", "2~4");
        assertSpec(10000, "8~12", "2~4");
        assertSpec(Integer.MAX_VALUE, "8~12", "2~4");
    }

    @Test
    void normalizeTarget_默认档与显式值() {
        assertEquals(LayoutRules.DEFAULT_WORD_COUNT_TARGET, LayoutRules.normalizeTarget(null));
        assertEquals(LayoutRules.DEFAULT_WORD_COUNT_TARGET, LayoutRules.normalizeTarget(-1));
        assertEquals(LayoutRules.DEFAULT_WORD_COUNT_TARGET, LayoutRules.normalizeTarget(0));
        assertEquals(2500, LayoutRules.normalizeTarget(2500));
    }

    private static void assertSpec(Integer target, String headings, String paras) {
        LayoutRules.SectionSpec s = LayoutRules.sectionSpec(target);
        assertEquals(headings, s.headings(), "目标 " + target + " 的小标题数档");
        assertEquals(paras, s.parasPerSection(), "目标 " + target + " 的每节段数档");
    }
}
