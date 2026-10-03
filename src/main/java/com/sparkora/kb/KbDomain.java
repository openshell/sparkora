package com.sparkora.kb;

import java.util.List;

/**
 * 通用知识库「领域标签」受控词表（10-03 E3）。
 *
 * <p>纯静态、无 Spring 依赖、可单测：领域维度是低频变更的**产品定义**，以代码常量固化
 * （可版本化、可复现），避免运行时配置漂移；改词表 = 改代码发版
 * （同 {@code com.sparkora.news.classify.NewsImageClassifier} 先例）。
 *
 * <p>写入侧统一 normalize：{@code trim} → 空白归 {@link #DEFAULT} → 非词表值**拒绝**
 * （抛 {@link IllegalArgumentException}，控制器映射 400 并附允许列表）。
 * 存量行由 Flyway {@code V6__kb_normalize.sql} 按「精确匹配，否则归通用」回填。
 */
public final class KbDomain {

    /** 默认领域（空白/未提供时）。 */
    public static final String DEFAULT = "通用";

    /** 受控词表（保序 = 前端下拉展示序）。 */
    private static final List<String> VALUES = List.of(
            "通用", "充电", "保养", "政策", "技术科普", "安全", "驾驶");

    private KbDomain() {
    }

    /** 受控词表（有序、只读视图；供前端下拉与展示）。 */
    public static List<String> all() {
        return VALUES;
    }

    /** 是否为受控值（trim 后精确匹配；null/空白视为 false）。 */
    public static boolean isValid(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        return !t.isEmpty() && VALUES.contains(t);
    }

    /**
     * 规范化：trim；空白 → {@link #DEFAULT}；非词表值抛 {@link IllegalArgumentException}（附允许列表）。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String t = raw.trim();
        if (!VALUES.contains(t)) {
            throw new IllegalArgumentException(
                    "领域标签不在受控词表内: " + t + "（可选: " + String.join("/", VALUES) + "）");
        }
        return t;
    }
}
