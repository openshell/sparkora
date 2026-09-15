package com.sparkora.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图库多标签 AND 筛选纯函数单测（09-15 img-classify，不连库）。
 * 覆盖：单标签向后兼容、双标签取交集、交集为空、归一（trim/去空/去重）、未指定标签返回 null。
 */
class ImageServiceTagFilterTest {

    /** 假标签查询：按名称返回预置 id 集（缺省空集）。 */
    private static java.util.function.Function<String, List<Long>> lookup(Map<String, List<Long>> data) {
        return name -> data.getOrDefault(name, List.of());
    }

    private static final Map<String, List<Long>> DATA = Map.of(
            "主题/销量", List.of(1L, 2L, 3L, 4L, 5L),
            "年份/2026", List.of(2L, 4L, 6L),
            "主题/社会责任", List.of(9L, 10L)
    );

    @Test
    void 未指定标签返回null_不筛() {
        assertNull(ImageService.resolveTagIds(null, lookup(DATA)));
        assertNull(ImageService.resolveTagIds(List.of(), lookup(DATA)));
        assertNull(ImageService.resolveTagIds(java.util.Arrays.asList("  ", null), lookup(DATA)));
    }

    @Test
    void 单标签等价旧单标签语义() {
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L), ImageService.resolveTagIds(List.of("主题/销量"), lookup(DATA)));
    }

    @Test
    void 双标签取AND交集() {
        assertEquals(Set.of(2L, 4L),
                ImageService.resolveTagIds(List.of("主题/销量", "年份/2026"), lookup(DATA)));
    }

    @Test
    void 交集为空返回空集() {
        assertTrue(ImageService.resolveTagIds(List.of("主题/社会责任", "年份/2026"), lookup(DATA)).isEmpty());
    }

    @Test
    void 标签名trim与去重保序() {
        assertEquals(Set.of(2L, 4L),
                ImageService.resolveTagIds(List.of(" 主题/销量 ", "主题/销量", "年份/2026"), lookup(DATA)));
    }

    @Test
    void 未知标签命中空集() {
        assertTrue(ImageService.resolveTagIds(List.of("主题/不存在"), lookup(DATA)).isEmpty());
    }
}
