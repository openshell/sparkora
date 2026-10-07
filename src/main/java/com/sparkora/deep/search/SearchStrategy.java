package com.sparkora.deep.search;

/**
 * WEB 搜索策略(10-04-web-fanout-merge B-R1)。
 *
 * <ul>
 *   <li>{@link #FIRST_HIT}:首个产出有效命中的 provider 即采信并停止(现状,默认,零回归);</li>
 *   <li>{@link #PRIMARY_FANOUT}:primary 组(配置集合 ∩ order)并行调用,合并后统一截断;
 *       primary 全空时才按 FIRST_HIT 逻辑对 fallback 组兜底。</li>
 * </ul>
 *
 * <p>由部署级配置 {@code sparkora.deep.web-fanout}({@code DEEP_WEB_FANOUT})决定,默认 {@code first_hit};
 * 运行时 {@code sparkora_setting} 不放开该开关(直接决定成本)。
 */
public enum SearchStrategy {

    /** 单源短路(默认,现有部署逐位等价)。 */
    FIRST_HIT,
    /** 多源并行聚合。 */
    PRIMARY_FANOUT;

    /**
     * 大小写不敏感解析;空白回退 {@link #FIRST_HIT};未知值抛 {@link IllegalArgumentException}
     * (配置错误明确暴露,不静默吞掉——与 {@link WebProvider#from(String)} 同风格)。
     */
    public static SearchStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) return FIRST_HIT;
        try {
            return SearchStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的搜索策略: " + raw);
        }
    }
}
