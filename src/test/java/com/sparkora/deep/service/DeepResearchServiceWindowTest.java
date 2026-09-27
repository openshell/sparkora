package com.sparkora.deep.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 研究窗口选择器单测(09-27-brief-writing-linkage-fix R3/AC-03)。
 *
 * <p>断言:keyQuestions 数 &gt; maxAgents 且含背景型问题时,窗口必含背景题;
 * 背景题多于预算时仍按原序取背景题;toolHints 索引对齐(返回升序索引)。
 */
class DeepResearchServiceWindowTest {

    @Test
    void 宽主题_背景题在尾部_仍进入窗口() {
        // 7 条参数题 + 1 条兜底背景题(append 尾部)= 8,maxAgents=6
        List<String> qs = new ArrayList<>(List.of(
                "价格是多少", "尺寸参数如何", "续航多长", "电机功率多少",
                "充电速度多快", "安全配置有哪些", "竞品对比如何",
                "该主题的行业背景、企业战略与长期目标是什么?"));
        List<Integer> window = DeepResearchService.selectResearchWindow(qs, 6);

        assertEquals(6, window.size(), "窗口大小受 maxAgents 约束");
        assertTrue(window.contains(7), "背景题必须保有研究窗口(不被尾部截断丢弃)");
        // 原序稳定
        for (int i = 1; i < window.size(); i++) {
            assertTrue(window.get(i - 1) < window.get(i), "返回索引必须升序(原序稳定)");
        }
    }

    @Test
    void 背景题少于预算_其余按原序补足() {
        List<String> qs = new ArrayList<>(List.of(
                "价格是多少", "行业背景与意义?", "续航多长", "尺寸参数"));
        List<Integer> window = DeepResearchService.selectResearchWindow(qs, 3);

        assertEquals(3, window.size());
        assertTrue(window.contains(1), "背景题优先保留");
        assertEquals(List.of(0, 1, 2), window, "背景题 + 原序前 2 条参数题");
    }

    @Test
    void 背景题多于预算_按原序取前N条背景题() {
        List<String> qs = new ArrayList<>(List.of(
                "行业背景如何?", "战略目标是什么?", "规划布局如何?", "价格是多少"));
        List<Integer> window = DeepResearchService.selectResearchWindow(qs, 2);

        assertEquals(List.of(0, 1), window, "背景题多于预算时按原序取前 N 条");
    }

    @Test
    void 无背景题_等价于截前N条() {
        List<String> qs = new ArrayList<>(List.of("价格是多少", "续航多长", "尺寸参数"));
        assertEquals(List.of(0, 1), DeepResearchService.selectResearchWindow(qs, 2));
    }

    @Test
    void 问题数不超过预算_全量原序() {
        List<String> qs = new ArrayList<>(List.of("价格是多少", "行业背景如何", "续航多长"));
        assertEquals(List.of(0, 1, 2), DeepResearchService.selectResearchWindow(qs, 6));
    }

    @Test
    void 空列表与非法预算_返回空() {
        assertTrue(DeepResearchService.selectResearchWindow(List.of(), 6).isEmpty());
        assertTrue(DeepResearchService.selectResearchWindow(null, 6).isEmpty());
        assertTrue(DeepResearchService.selectResearchWindow(List.of("价格"), 0).isEmpty());
    }
}
