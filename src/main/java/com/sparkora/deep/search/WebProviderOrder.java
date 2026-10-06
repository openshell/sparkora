package com.sparkora.deep.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部搜索 provider 顺序(策略)值对象(09-25-brief-web-search)。
 *
 * <p>输入为逗号分隔的 provider 串(如 {@code TAVILY,SEARXNG});解析规则:
 * <ul>
 *   <li>去空白、大小写不敏感、按出现顺序去重(重复项只保留首次);</li>
 *   <li>未知值明确拒绝(抛 {@link IllegalArgumentException}),不静默吞掉;</li>
 *   <li>空或缺省回退代码默认 {@code TAVILY,SEARXNG}(=TAVILY_FIRST,2026-09-25 反转旧决策)。</li>
 * </ul>
 * 约定:字符串 {@code TAVILY,SEARXNG} 即语义策略 {@code TAVILY_FIRST};{@code SEARXNG,TAVILY} 即 {@code SEARXNG_FIRST}；
 * 10-04 A/B 起多 provider 并行聚合策略语义为 {@code PRIMARY_FANOUT}。
 */
public record WebProviderOrder(List<WebProvider> providers) {

    /** 代码默认顺序:TAVILY 优先、SEARXNG 兜底(推翻 2026-09-15「SEARXNG 优先」决策)。 */
    public static final String DEFAULT_RAW = "TAVILY,SEARXNG";

    /** TAVILY_FIRST 语义标签。 */
    public static final String TAVILY_FIRST = "TAVILY_FIRST";
    /** SEARXNG_FIRST 语义标签。 */
    public static final String SEARXNG_FIRST = "SEARXNG_FIRST";
    /** 多源并行聚合语义标签(10-04 A 预留,B 的 PRIMARY_FANOUT 策略回落此标签)。 */
    public static final String PRIMARY_FANOUT = "PRIMARY_FANOUT";

    public WebProviderOrder {
        providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * 解析 provider 顺序;空白输入回退 {@link #DEFAULT_RAW}(部署/运行时均未配置时的安全默认)。
     */
    public static WebProviderOrder parse(String csv) {
        if (csv == null || csv.isBlank()) return defaults();
        Set<WebProvider> dedup = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (part.isBlank()) continue;
            dedup.add(WebProvider.from(part));   // 未知值抛异常,不静默
        }
        if (dedup.isEmpty()) return defaults();
        return new WebProviderOrder(new ArrayList<>(dedup));
    }

    /** 默认 TAVILY_FIRST。 */
    public static WebProviderOrder defaults() {
        return parse(DEFAULT_RAW);
    }

    /** 是否存在指定 provider。 */
    public boolean contains(WebProvider p) {
        return providers.contains(p);
    }

    /** 规范化后的 provider 串(逗号分隔,落库/日志/响应统一口径)。 */
    public String raw() {
        return providers.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(DEFAULT_RAW);
    }

    /**
     * 语义策略标签(10-04 扩为三值):
     * <ul>
     *   <li>顺序含 {@link WebProvider#SERPER}(三源/新组合)→ {@link #PRIMARY_FANOUT};
     *       <b>必须</b>如此:否则加 SERPER 后首元素非 SEARXNG 的顺序会被一律误标 {@code TAVILY_FIRST};</li>
     *   <li>其余沿用两值逻辑(首元素 SEARXNG → {@code SEARXNG_FIRST},否则 {@code TAVILY_FIRST});</li>
     *   <li>B 的 PRIMARY_FANOUT 策略(web-fanout 开启)在快照层同样回落此标签。</li>
     * </ul>
     * 纯 legacy 顺序({@code TAVILY,SEARXNG} / {@code SEARXNG,TAVILY})的标签逐字不变(零回归)。
     */
    public String strategyLabel() {
        if (providers.contains(WebProvider.SERPER)) return PRIMARY_FANOUT;
        if (!providers.isEmpty() && providers.get(0) == WebProvider.SEARXNG) return SEARXNG_FIRST;
        return TAVILY_FIRST;
    }
}
