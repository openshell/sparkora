package com.sparkora.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 10-02-fix-meta-leak-in-article-body R3:正文内部元话语确定性清洗(prompt 之外的兜底防线)。
 *
 * <p><b>要解决的线上缺陷</b>:简报侧 {@code fact_risks[].suggestion} 是写给<b>作者</b>的第二人称祈使指令
 * (「此表述必须删除或改为 X」「建议正文以 Y 为主」),写作链路整块注入 user prompt 后,模型会把它
 * <b>改写成第三人称陈述</b>放进正文——祈使句变成「对读者的客观事实陈述」,于是「手册未披露…完成率无法计算」
 * 被当结论说出来(version 44 第 17/25 段实测)。
 *
 * <p><b>清洗粒度</b>:句级删除(不是行级、不是整篇丢弃):
 * <ol>
 *   <li>按 Markdown 段落切分(空行分隔),段内换行结构保留;</li>
 *   <li>段内按中文句读({@code 。！？!?；;} + 闭合引号/括号)切句;行首为 Markdown 块(列表/有序项/标题/引用/表格行)
 *       的行前换行视为句界,防止删除一条泄漏句时连带删掉相邻列表项、表格行或标题行;</li>
 *   <li>逐句匹配 {@link #META_PATTERNS},命中即丢弃该句(整句删,不做改写——改写等于二次创作,风险更高);
 *       命中的是<b>软换行多行块</b>时收窄到行级删除,不牵连块内其他正常行(见 {@link #appendNarrowed});</li>
 *   <li>段内句子全部被删 → 整段丢弃(不留空标题下的空段);</li>
 *   <li>重组:段间空行压成 1 个、段尾空白裁剪,<b>未命中句子逐字不动</b>。</li>
 * </ol>
 *
 * <p><b>零命中 = 零改写</b>:没有任何句子命中时原样返回入参(连空白归一都不做),避免无谓改动已发布正文;
 * 段落重组(空行压缩)只在确有删除时发生。
 *
 * <p><b>保守原则(宁漏不误伤)</b>:每条模式都是「内部词 + 缺失/否定」的组合特征,不匹配「详细参数以官方发布为准」
 * 「据媒体报道」这类正常归因句;纯静态、无状态、无 Spring 依赖、可单测(同 {@link LayoutRules} 范式)。
 *
 * <p><b>接入点</b>:深度写作 {@code DeepWriterService.write}(必接,清洗在数值回查之前)与多版本主题分支
 * {@code VersionService.generateOne}(仿写分支不接——正文源自用户原文,二次清洗有误删作者原意风险)。
 * 两条链路都用 {@link #cleanForPersist} 而非 {@link #clean}:清洗致空时回退原文,不落空正文。
 * 被删原文经 {@link CleanResult#removed()} 交调用方 {@code log.warn} 落审计(不落库、不给前端,零契约变更)。
 */
public final class MetaLeakCleaner {

    private MetaLeakCleaner() {}

    /**
     * 清洗结果。{@code content} 为清洗后正文,{@code removed} 为被删句子原文(仅供日志审计)。
     *
     * @param content 清洗后的 Markdown 正文(无命中时逐字等于入参)
     * @param removed 被删句子原文,按删除顺序(可能为空列表,不会是 null)
     */
    public record CleanResult(String content, List<String> removed) {}

    /**
     * 内部元话语模式表(句内 {@code find()} 命中即删句)。
     *
     * <p>三组特征:①「事实手册/知识库」等内部工件被当作叙述对象(主泄漏源);
     * ②「无法计算/不应作为结论/缺乏可比数据」等缺失与否定话术;
     * ③事实手册行格式泄漏(置信度数值、单一 WEB 源)。
     */
    private static final List<Pattern> META_PATTERNS = List.of(
            // ① 内部工件当叙述对象(主泄漏源;「据媒体报道」等正常归因不命中)
            Pattern.compile("(?:事实)?手册(?:未|没有|无)(?:提供|披露|收录|覆盖|给出|包含|记载|列出)"),
            Pattern.compile("未(?:收录|收载)(?:在|于)?(?:事实)?手册"),
            Pattern.compile("(?:据|根据)(?:事实)?手册"),
            Pattern.compile("知识库(?:检索)?(?:未|没有|无)(?:覆盖|提供|命中|收录|返回)"),
            Pattern.compile("知识库(?:检索)?(?:失败|不可用|无结果)"),
            // ② 缺失 / 不可计算 / 不下结论话术
            Pattern.compile("(?:无法|不能|不可)计算"),
            Pattern.compile("(?:本文|本报告|下文|正文)(?:不|无法)(?:作|提供|给出)(?:结论|判断|断言)"),
            Pattern.compile("(?:不应|不得|不能)作为(?:结论|输出|断言)"),
            Pattern.compile("(?:本文|正文|文中)?(?:无|没有|未)(?:数据|依据|出处|证据)(?:支撑|来源|可考)"),
            Pattern.compile("缺乏[^。]{0,8}可比(?:数据|口径|项)"),
            Pattern.compile("未提供[^。]{0,8}数据"),
            Pattern.compile("(?:也没有|并无|尚无|暂无|未见)[^。]{0,10}(?:数据|排名|份额|口径|完成率)"),
            // ③ 事实手册行格式泄漏(置信度/单一来源)
            Pattern.compile("置信\\s*0\\.\\d+"),
            Pattern.compile("仅单一\\s*WEB\\s*源"),
            Pattern.compile("待核实(?![^。]{0,20}(?:官网|官方))")
    );

    /** 句末标点(句界)。 */
    private static final String TERMINATORS = "。！？!?；;";
    /** 句末标点之后允许紧跟的闭合符号(引号/书名号/括号),避免把它们留成孤行。 */
    private static final String CLOSERS = "」』”’）)】》]";
    /**
     * Markdown 块起始字符(其行前换行视为句界,防跨行误删)。
     *
     * <p>{@code |} 为表格行:表格是空行分隔的独立段,若不逐行设界,一行含元话语会让
     * <b>整张表</b>(表头 + 分隔行 + 其余正常行)作为一个「句子」被整删。
     */
    private static final String BLOCK_STARTS = "-*+#>|";
    /** 段首空行(只裁空行,不裁行首缩进)。 */
    private static final Pattern LEADING_BLANK_LINES = Pattern.compile("^(?:[ \\t\\u3000]*\\n)+");

    /**
     * 落库前清洗:保证清洗后<b>仍有正文</b>——整篇皆为元话语(清洗后空白/只剩标题)时回退原文,绝不落空壳正文。
     *
     * <p>调用方语义(两条正文生成链路一致):先做 {@code contentMd.isBlank()} 之类的<b>非空前置校验</b>再清洗,
     * 但清洗可能把正文删空或删到只剩标题(极端情况:AI 产出整篇都是「手册未提供…」这类句子)。此时丢弃原文等于毁掉整次生成,
     * 故回退原文并保留 {@code removed} 记录,由调用方 {@code log.warn} 留痕交人工判断。
     *
     * @param markdown 原始正文 Markdown
     * @return {@link CleanResult};清洗后无正文且原文有正文时 {@code content} 为原文
     */
    public static CleanResult cleanForPersist(String markdown) {
        CleanResult r = clean(markdown);
        if (!r.removed().isEmpty() && isBodyless(r.content()) && !isBodyless(markdown)) {
            return new CleanResult(markdown, r.removed());
        }
        return r;
    }

    /** 正文是否「无内容」:null/空白,或只剩 Markdown 标题行(对读者等同空文)。 */
    private static boolean isBodyless(String content) {
        if (content == null || content.isBlank()) return true;
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && !isHeading(t)) return false;
        }
        return true;
    }

    /**
     * 清洗正文中的内部元话语(句级删除,零改写)。
     *
     * @param markdown 原始正文 Markdown(null/空白原样返回)
     * @return {@link CleanResult}(无命中时 content 与入参逐字相等,removed 为空)
     */
    public static CleanResult clean(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new CleanResult(markdown, List.of());
        }
        // 段落 = 空行分隔(保留空行内的换行结构);连续空行在重组时压成 1 个
        String[] paragraphs = markdown.split("\n\\s*\n", -1);
        List<String> kept = new ArrayList<>(paragraphs.length);
        List<String> removed = new ArrayList<>();
        for (String raw : paragraphs) {
            String para = trimBlock(raw);
            if (para.isEmpty()) continue;
            StringBuilder out = new StringBuilder(para.length());
            for (String sentence : splitSentences(para)) {
                // Markdown 标题行不参与删句(保护结构,标题几乎不会承载元话语)
                if (isHeading(sentence) || !isMeta(sentence)) {
                    out.append(sentence);
                } else if (sentence.indexOf('\n') >= 0) {
                    // 软换行多行块命中 → 收窄到行级删除:整块删会把相邻正常行一起带走
                    // (AI 输出的长段落常在句中断行,块级删除的误伤面可达整个软换行段)
                    appendNarrowed(out, sentence, removed);
                } else {
                    removed.add(stripLineBreak(sentence).trim());
                }
            }
            String cleanedPara = trimBlock(out.toString());
            // 段内句子全被删 → 整段丢弃,避免留下空标题下的空段
            if (!cleanedPara.isEmpty()) kept.add(cleanedPara);
        }
        // 零命中 = 零改写:原样返回入参(含首尾空白),保证「未命中则逐字不变」与幂等
        if (removed.isEmpty()) return new CleanResult(markdown, List.of());
        return new CleanResult(String.join("\n\n", kept), List.copyOf(removed));
    }

    /** 句内是否命中元话语。 */
    private static boolean isMeta(String sentence) {
        for (Pattern p : META_PATTERNS) {
            if (p.matcher(sentence).find()) return true;
        }
        return false;
    }

    /**
     * 命中多行软换行块时的行级收窄删除:逐行判定,命中行删、其余行原样保留(含换行结构)。
     *
     * <p>取舍(宁漏不误伤):块内元话语若跨行断开,按行删会留下半句残片——但那只在
     * 「泄漏句的行尾没有句读、且上一行有正常内容」时发生;块级删则必然带走相邻正常行,代价更大。
     */
    private static void appendNarrowed(StringBuilder out, String sentence, List<String> removed) {
        String[] lines = sentence.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (isMeta(line)) removed.add(line.trim());
            else kept.add(line);
        }
        out.append(String.join("\n", kept));
    }

    /**
     * 段内切句:句末标点(+其后闭合符号)为句界;行首为 Markdown 块(列表/有序项/标题/引用/表格行)时其前换行亦为句界
     * (软换行不切,跨行软折的句子保持完整——这类块若命中元话语,由 {@link #appendNarrowed} 收窄到行级删除)。
     */
    private static List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = para.length();
        while (i < n) {
            char c = para.charAt(i);
            if (c == '\n') {
                boolean boundary = (cur.length() > 0 && endsWithTerminator(cur)) || startsBlock(para, i + 1);
                if (boundary && cur.length() > 0) {
                    cur.append('\n');
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (cur.length() == 0 && !out.isEmpty()) {
                    // 上一句已收尾:换行归属上一句,删该句时不丢行结构
                    out.set(out.size() - 1, out.get(out.size() - 1) + "\n");
                } else {
                    cur.append(c);
                }
                i++;
                continue;
            }
            cur.append(c);
            if (TERMINATORS.indexOf(c) >= 0) {
                while (i + 1 < n && CLOSERS.indexOf(para.charAt(i + 1)) >= 0) {
                    i++;
                    cur.append(para.charAt(i));
                }
                out.add(cur.toString());
                cur.setLength(0);
            }
            i++;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 段尾是否为句末标点(该换行即句界)。 */
    private static boolean endsWithTerminator(StringBuilder cur) {
        char last = cur.charAt(cur.length() - 1);
        return TERMINATORS.indexOf(last) >= 0 || CLOSERS.indexOf(last) >= 0;
    }

    /** from(不含)处开始的下一行是否为 Markdown 块起始(列表项/有序项/标题/引用/表格行)。 */
    private static boolean startsBlock(String s, int from) {
        int i = from;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        if (BLOCK_STARTS.indexOf(c) >= 0) return true;
        // 有序列表 "1. " / "1) ":数字本身不在 BLOCK_STARTS 里,单独判定避免整段被当成一个句子
        if (c >= '1' && c <= '9' && i + 1 < s.length() && (s.charAt(i + 1) == '.' || s.charAt(i + 1) == ')')) {
            int j = i + 2;
            while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
            return j > i + 2;   // 点/括号后须有空白,避免误判 "1.5万辆" 这类正文数字
        }
        return false;
    }

    /** 句子是否为 Markdown 标题行(不删)。 */
    private static boolean isHeading(String sentence) {
        return sentence.stripLeading().startsWith("#");
    }

    /** 段落首尾空白裁剪(只裁首部空行与尾部空白,不破坏行首缩进)。 */
    private static String trimBlock(String s) {
        String out = LEADING_BLANK_LINES.matcher(s).replaceAll("");
        int end = out.length();
        while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) end--;
        return out.substring(0, end);
    }

    /** 去掉句尾换行(日志可读性,不改内容判定)。 */
    private static String stripLineBreak(String sentence) {
        return sentence.endsWith("\n") ? sentence.substring(0, sentence.length() - 1) : sentence;
    }
}
