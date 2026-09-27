package com.sparkora.service;

/**
 * 09-27-shared-layout-rules:正文排版「## 小标题分节」档位(深度写作与多版本/仿写共用)。
 *
 * <p>只共享<b>分类结果</b>,不统一两链路的文案格式:深度写作保持单行分号串
 * ({@code com.sparkora.deep.service.DeepWriterService.layoutRule}),{@code VersionService}
 * 保持三段 bullet 列表。分档表与深度链路现状逐值等价。
 *
 * <p>纯静态、无状态、无 Spring 依赖,可单测。
 */
public final class LayoutRules {

    private LayoutRules() {}

    /** 目标字数默认值(两链路既有口径一致:null/≤0 → 1500)。 */
    public static final int DEFAULT_WORD_COUNT_TARGET = 1500;

    /** 分节档位(小标题数区间 + 每节段数区间,纯字符串直接进 prompt)。 */
    public record SectionSpec(String headings, String parasPerSection) {}

    /** 目标字数归一:null/≤0 → 1500(与两链路既有口径一致,不抛)。 */
    public static int normalizeTarget(Integer targetWords) {
        return (targetWords == null || targetWords <= 0) ? DEFAULT_WORD_COUNT_TARGET : targetWords;
    }

    /**
     * 09-27-deep-writing-adaptive-sections R2/R3:按目标字数取分节档位(纯函数,无副作用,不抛)。
     *
     * <p>null/≤0 → 按 1500(中档 3~5);边界:800→2~3,801→3~5,1800→3~5,1801→5~8,
     * 3000→5~8,3001→8~12,10000→8~12(每节 2~3,顶档 2~4)。
     */
    public static SectionSpec sectionSpec(Integer targetWords) {
        int n = normalizeTarget(targetWords);
        if (n <= 800) return new SectionSpec("2~3", "2~3");
        if (n <= 1800) return new SectionSpec("3~5", "2~3");
        if (n <= 3000) return new SectionSpec("5~8", "2~3");
        return new SectionSpec("8~12", "2~4");
    }
}
