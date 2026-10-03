package com.sparkora.deep.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 事实手册近似 claim 归并工具(09-25-fact-claim-merge)。
 *
 * <p>纯静态、无状态、无新增依赖、不调 LLM：用「数值签名硬前提 + 字符 n-gram/最长公共片段相似度」
 * 判定两条 claim 是否指向同一事实。数值冲突(如「第2000座」vs「第1500座」)与「一侧有数字一侧没有」
 * 一律不得合并，正确性优先于召回。
 *
 * <p>不复用 {@code ImitationService} 的相似度方法:其方法为包级可见(跨包不可用)，且语义不同
 * (长文仿写查重 vs 短 claim 归并)，独立实现避免改动仿写逻辑引入回归。
 */
final class ClaimSimilarity {

    /**
     * 有数值 claim 的相似度阈值:数值签名已把事实锁定到同一组数值，措辞差异容忍度可放低。
     */
    static final double TH_NUMERIC = 0.45;

    /**
     * 无数值(定性)claim 的相似度阈值:仅措辞级差异才允许合并，阈值取高以防定性事实误并
     * (用户已确认「误合并防护优先于召回」)。
     */
    static final double TH_TEXT = 0.70;

    /** 字符 n-gram 长度:中文短句用 3-gram 兼顾区分度与召回。 */
    private static final int NGRAM = 3;

    /** 规范化后短于此长度视为信息不足，相似度直接 0，避免「短串包含长串」式误判。 */
    private static final int MIN_LEN = 4;

    private ClaimSimilarity() {
    }

    /**
     * 规范化:去 Markdown 图片/链接与 HTML 标签、去标题井号/强调符，仅保留字母(含中文)与数字并小写化。
     * 供相似度/数值判断使用；claim 很短，规范化后直接做字符 n-gram。
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("(?s)!\\[[^\\]]*\\]\\([^)]*\\)", "");   // Markdown 图片
        s = s.replaceAll("(?s)<[^>]*>", "");                     // HTML 标签(含 img)
        s = s.replaceAll("(?s)\\[[^\\]]*\\]\\([^)]*\\)", "");    // Markdown 链接
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");                 // 标题井号
        s = s.replaceAll("[*_~`>#|\\[\\]]", "");                 // 强调/表格等标记
        s = s.replaceAll("[^\\p{L}\\p{Nd}]+", "");               // 仅留字母与数字(去标点/空白)
        return s.toLowerCase();
    }

    /**
     * 抽取文本中的数值签名(10-03 E5：实现上移 {@link com.sparkora.ai.NumericSignature}，行为逐字不变)。
     *
     * <p>用 {@link BigDecimal} 归一比较，使 {@code 200000} 与 {@code 20万} 视为同一数值。
     * 解析失败时回退为原 token 字符串，绝不抛出异常。
     *
     * @param texts 待抽取文本(claim 与 value 可一并传入，数值来自两者任一处)
     */
    static List<String> numberValues(String... texts) {
        return com.sparkora.ai.NumericSignature.numberValues(texts);
    }

    /**
     * 相似度 0..1:3-gram 重合率(Jaccard 风格，|A∩B| / max(|A|,|B|))与最长公共连续片段占比
     * (最长公共片段 / min(len))的算术平均。
     *
     * <p>取平均而非 max:max 会让单条共享长片段(如前缀)主导、把语义相反的定性 claim 误判为相似；
     * 平均同时要求整体 n-gram 重合与连续片段都较高，更契合「措辞级差异」的归并目标。
     * 任一侧规范化后短于 {@link #MIN_LEN} 字 → 0。
     */
    static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.length() < MIN_LEN || nb.length() < MIN_LEN) return 0.0;
        double gram = ngramOverlap(na, nb);
        double run = runRatio(na, nb);
        return (gram + run) / 2.0;
    }

    /** 3-gram 重合率:|A∩B| / max(|A|,|B|)(Jaccard 风格，避免长度差放大)。 */
    private static double ngramOverlap(String a, String b) {
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        int max = Math.max(ga.size(), gb.size());
        if (max == 0) return 0.0;
        int hit = 0;
        for (String g : ga) if (gb.contains(g)) hit++;
        return (double) hit / max;
    }

    private static Set<String> grams(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + NGRAM <= s.length(); i++) out.add(s.substring(i, i + NGRAM));
        return out;
    }

    /** 最长公共连续片段长度 / min(len)。 */
    private static double runRatio(String a, String b) {
        int min = Math.min(a.length(), b.length());
        if (min == 0) return 0.0;
        int best = 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = ca == b.charAt(j - 1) ? prev[j - 1] + 1 : 0;
                if (cur[j] > best) best = cur[j];
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return (double) best / min;
    }

    /**
     * 判定两条 claim(+value)是否指向同一事实。
     *
     * <p>步骤:
     * <ol>
     *   <li>两条 claim 规范化后均非空,否则 false;</li>
     *   <li>claim <b>原文(trim 后)完全相同</b> → 直接 true:严格保留旧「按 claim 精确匹配分组」的
     *       既有行为——含 KB+WEB 同 claim 冲突裁决路径(即便 value 有异也先聚成一条再走 KB 胜出),
     *       该情形下「异说」由冲突裁决/warnings 处理,不属于近似归并；</li>
     *   <li><b>数值签名硬前提</b>(对所有非原样相同的近似 claim):claim+value 抽出的数值集合
     *       <b>完全相等</b>——一侧有数值、另一侧没有视为不等；数值冲突(2000 vs 1500)不等 → 绝不合并。
     *       注意 {@link #normalize} 会丢弃小数点/千分位(「1.5万」与「15万」、「2.9米」与「29米」规范化后
     *       相同)，故近似分支绝不能先按规范化相同短路,否则会把数值冲突误并；</li>
     *   <li>满足硬前提后按阈值判定:有数值 ≥ {@link #TH_NUMERIC}；无数值 ≥ {@link #TH_TEXT}。</li>
     * </ol>
     */
    static boolean sameClaim(String c1, String v1, String c2, String v2) {
        String n1 = normalize(c1);
        String n2 = normalize(c2);
        if (n1.isEmpty() || n2.isEmpty()) return false;
        // 旧精确匹配行为:claim 原文(trim)完全相同 → 同一事实(含 KB+WEB 冲突裁决路径,不因 value 异而拆)
        if (c1.trim().equals(c2.trim())) return true;
        // 近似 claim 硬前提:数值签名一致(数值冲突/一侧缺失一律不合并)。
        // 必须先于「规范化后相同」判断——normalize 去掉了小数点/千分位,
        // 「1.5万」与「15万」等数值冲突文本会规范化成同一字符串,先短路即误并。
        List<String> s1 = numberValues(c1, v1);
        List<String> s2 = numberValues(c2, v2);
        if (!s1.equals(s2)) return false;
        // 相似度只看 claim(value 已参与数值签名；再拼进 value 会因格式差异稀释措辞相似度)
        double score = similarity(c1, c2);
        return score >= (s1.isEmpty() ? TH_TEXT : TH_NUMERIC);
    }
}
