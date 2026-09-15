package com.sparkora.news.classify;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新闻图片主题分类器纯函数单测（09-15 img-classify，不连库）。
 * 覆盖：一词多主题重叠、保序、边界（null/空/无命中）、年份派生、标签命名空间。
 */
class NewsImageClassifierTest {

    @Test
    void 一词多主题_海外销售同时命中销量与出海() {
        List<String> themes = NewsImageClassifier.classifyThemes("比亚迪8月份销售440293辆 海外销售超18万辆，再创历史新高！");
        assertTrue(themes.contains("销量"), () -> "应命中销量: " + themes);
        assertTrue(themes.contains("出海"), () -> "应命中出海: " + themes);
    }

    @Test
    void 命中主题保序_按词表顺序() {
        List<String> themes = NewsImageClassifier.classifyThemes("海外销售再创新高");
        assertEquals(List.of("销量", "出海"), themes);
    }

    @Test
    void 无命中返回空列表() {
        assertTrue(NewsImageClassifier.classifyThemes("比亚迪第三艘滚装船顺利启航").isEmpty());
    }

    @Test
    void 标题空或null返回空列表() {
        assertTrue(NewsImageClassifier.classifyThemes(null).isEmpty());
        assertTrue(NewsImageClassifier.classifyThemes("   ").isEmpty());
    }

    @Test
    void 标签命名空间前缀() {
        List<String> tags = NewsImageClassifier.toTags("比亚迪7月份销售41.9万辆", LocalDate.of(2026, 8, 3));
        assertEquals(List.of("主题/销量", "主题/里程碑", "年份/2026"), tags);
    }

    @Test
    void 无主题时只出年份标签() {
        List<String> tags = NewsImageClassifier.toTags("比亚迪第三艘滚装船顺利启航", LocalDate.of(2025, 1, 2));
        assertEquals(List.of("年份/2025"), tags);
    }

    @Test
    void 日期为空只出主题标签() {
        List<String> tags = NewsImageClassifier.toTags("比亚迪与中国石化深化战略合作", (LocalDate) null);
        assertEquals(List.of("主题/合作签约"), tags);
    }

    @Test
    void 标题与日期均空返回空列表() {
        assertTrue(NewsImageClassifier.toTags(null, (LocalDate) null).isEmpty());
    }

    @Test
    void LocalDateTime重载取日期年份() {
        List<String> tags = NewsImageClassifier.toTagsFrom("比亚迪全系新能源车型热销哥伦比亚", LocalDateTime.of(2023, 11, 30, 9, 0));
        assertTrue(tags.contains("年份/2023"), () -> "应含年份标签: " + tags);
        assertTrue(tags.contains("主题/出海"), () -> "应含出海: " + tags);
    }

    @Test
    void 词表固定九主题且有序() {
        assertEquals(List.of("销量", "出海", "合作签约", "技术发布", "里程碑",
                "荣誉", "财报ESG", "车展上市", "社会责任"),
                List.copyOf(NewsImageClassifier.themes().keySet()));
    }
}
