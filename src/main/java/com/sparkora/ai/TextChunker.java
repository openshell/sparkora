package com.sparkora.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切块器（纯静态、无 Spring 依赖、可单测；09-27 知识域写入侧统一）。
 *
 * <p>KB（通用知识）与 NEWS（官方新闻）两域原本各自持有近逐行复制的 {@code chunkContent} + {@code splitSentences}；
 * 本类收敛为唯一实现（{@code splitSentences} 全库仅此一处）。两域的差异被参数化：
 * <ul>
 *   <li>{@code header}：首行锚点文本由调用方构造（KB「知识：title（domain）」/ NEWS「新闻：title（date）」），
 *       本类不解析、不感知领域；</li>
 *   <li>{@code titlePresent} + {@code keepTitleWhenEmpty}：正文为空时的兜底语义——
 *       KB 恒保留标题块（{@code keepTitleWhenEmpty=true}）；NEWS 仅标题非空才保留
 *       （{@code keepTitleWhenEmpty=false} 且 {@code titlePresent}）。</li>
 * </ul>
 *
 * <p>算法（逐行搬自原 KB 实现）：
 * <ol>
 *   <li>正文按空行分段；段内换行转空格并 trim；</li>
 *   <li>单段 ≤{@link #MAX_BODY_LEN} 直接成块；超长段按句读切分合并至 ≤{@link #MAX_BODY_LEN}；</li>
 *   <li>每块首行追加 {@code header}；正文全为符号等极端情况兜底 header 块。</li>
 * </ol>
 *
 * <p><b>句读集合按域参数化（09-27，保 AC4 逐块一致）</b>：KB 与 NEWS 原实现的句读集合并不相同——
 * KB 实际只切 {@code 。；!?}，NEWS 另含全角 {@code ！？} 与半角 {@code ;}（KB 侧历史笔误）。统一时若取
 * 二者超集，会改变 KB 的切块边界（违反「改造后产出逐块不变」），故把集合作为参数由调用方传入，
 * 两域各自保持改造前行为；{@link #splitSentences} 仍是全库唯一实现。
 *
 * <p><b>滑动重叠（10-03 E2）</b>：为减少跨块边界语义被切断，新增 6 参重载 {@link #chunk(String, String,
 * boolean, boolean, String, int)}。只要前一块**严格长于** {@code overlapChars}，就取其一个**尾部片段**
 * （≤{@code overlapChars}，优先从片段内首个句读分隔符之后开始以对齐句读边界）作为**下一块的前缀**，
 * 形成相邻块滑动重叠。作用范围是**所有相邻产出块**（短段落分块 + 超长段句读合并两路），
 * 因为跨块边界语义损失主要发生在相邻块之间。约束：
 * <ul>
 *   <li><b>向后兼容</b>：旧 5 参 {@link #chunk} 委托 {@code overlapChars=0}，逐块等价改造前行为，
 *       既有调用方与测试零回归；KB/NEWS 服务层显式传 {@link #DEFAULT_OVERLAP_CHARS} 启用；</li>
 *   <li><b>块体仍 ≤ {@link #MAX_BODY_LEN}</b>：重叠前缀只在新块尚有余量时注入，放不下则本边界不重叠；</li>
 *   <li><b>不整块重复</b>：仅当上一块严格长于 {@code overlapChars} 时才取真后缀（前缀严格短于上一块）；</li>
 *   <li><b>不新增块数</b>：重叠只给下一块加前缀，不额外切出块（短段落场景块数不变）。</li>
 * </ul>
 */
public final class TextChunker {

    /** 单块正文目标上限（不含首行标题）。 */
    public static final int MAX_BODY_LEN = 500;

    /**
     * 默认滑动重叠字符数（10-03 E2）。保守取值：约 1/8 块长，只补齐跨块边界的短语境，
     * 避免块数膨胀与检索噪声。KB/NEWS 服务层显式启用；旧 5 参 {@link #chunk} 默认 0（无重叠）。
     */
    public static final int DEFAULT_OVERLAP_CHARS = 60;

    /** KB（通用知识）历史句读集合：{@code 。；!?}（保持改造前逐块一致，不扩集）。 */
    public static final String KB_SEPARATORS = "。；!?";

    /** NEWS（官方新闻）历史句读集合：{@code 。；;！!？?}（KB 的超集，另含全角！？与半角;）。 */
    public static final String NEWS_SEPARATORS = "。；;！!？?";

    private TextChunker() {
    }

    /**
     * 切块（无滑动重叠；等价改造前行为，向后兼容入口）。
     *
     * @param header             每块首行锚点（调用方构造）
     * @param content            正文（可空/全空白）
     * @param titlePresent       标题是否非空（NEWS 空正文语义用；KB 传 true）
     * @param keepTitleWhenEmpty 正文为空时是否恒保留标题块（KB=true；NEWS=false，仅 titlePresent 时保留）
     * @param sentenceSeparators 句读分隔符集合（KB={@link #KB_SEPARATORS}；NEWS={@link #NEWS_SEPARATORS}）
     * @return 切块结果；空正文且不保留标题块时返回空列表
     */
    public static List<String> chunk(String header, String content,
                                     boolean titlePresent, boolean keepTitleWhenEmpty,
                                     String sentenceSeparators) {
        return chunk(header, content, titlePresent, keepTitleWhenEmpty, sentenceSeparators, 0);
    }

    /**
     * 切块（可选滑动重叠）。
     *
     * @param overlapChars 相邻块在句读合并流内的重叠字符数；{@code 0} = 无重叠（旧行为），
     *                     正数 = 启用（建议 {@link #DEFAULT_OVERLAP_CHARS}）
     * @see TextChunker 类注释「滑动重叠」
     */
    public static List<String> chunk(String header, String content,
                                     boolean titlePresent, boolean keepTitleWhenEmpty,
                                     String sentenceSeparators, int overlapChars) {
        List<String> out = new ArrayList<>();
        // 兜底:正文无有效字符(全空白)时按各域空正文语义处理
        if (content == null || content.strip().isEmpty()) {
            if (keepTitleWhenEmpty || titlePresent) out.add(header);
            return out;
        }
        int overlap = Math.max(0, Math.min(overlapChars, MAX_BODY_LEN / 2));
        // 空行分段
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<String> bodies = new ArrayList<>();
        StringBuilder carry = null;   // 超长段切分后的合并中转
        for (String pRaw : paragraphs) {
            String p = pRaw.replaceAll("\\s*\\n\\s*", " ").trim();   // 段内换行转空格
            if (p.isEmpty()) continue;
            if (p.length() <= MAX_BODY_LEN) {
                if (carry != null) { bodies.add(carry.toString()); carry = null; }
                bodies.add(p);
                continue;
            }
            // 超长段:按句读切分
            for (String s : splitSentences(p, sentenceSeparators)) {
                if (carry == null) {
                    carry = new StringBuilder(s);
                } else if (carry.length() + s.length() <= MAX_BODY_LEN) {
                    carry.append(s);
                } else {
                    bodies.add(carry.toString());
                    carry = new StringBuilder(s);
                }
            }
        }
        if (carry != null) bodies.add(carry.toString());
        // 滑动重叠:给每个非首块注入「上一块尾部片段」作为前缀(所有相邻块，含短段落分块与超长段合并块)
        String prev = null;
        for (String b : bodies) {
            String body = b;
            if (overlap > 0 && prev != null) {
                String seed = overlapTail(prev, sentenceSeparators, overlap);
                // 前缀 + 本块仍须 ≤ 上限；放不下则本边界不重叠（保守，不破块体约束）
                if (!seed.isEmpty() && seed.length() + b.length() <= MAX_BODY_LEN) body = seed + b;
            }
            out.add(header + "\n" + body);
            prev = b;
        }
        if (out.isEmpty()) out.add(header);   // 双保险:正文全为符号等极端情况
        return out;
    }

    /**
     * 取「上一块」尾部一个 ≤{@code overlapChars} 的重叠片段（滑动重叠用，10-03 E2）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code prev} 不严格长于 {@code overlapChars} 时返回空串——避免把整块当重叠（禁止整块重复）；</li>
     *   <li>先截取末 {@code overlapChars} 字符，再在片段内找首个句读分隔符；若该分隔符**不在片段末尾**，
     *       从其之后开始（使新块尽量以完整句读对齐）；</li>
     *   <li>片段内无分隔符，或唯一分隔符恰在片段末尾（整段即一个完整句）→ 直接返回该字符片段。
     *       重叠本就要跨边界补语境，允许片段的起点落在句中；禁止的是「整块重复」，
     *       而此处已保证片段严格短于上一块。</li>
     * </ul>
     */
    static String overlapTail(String prev, String separators, int overlapChars) {
        if (prev == null || overlapChars <= 0 || prev.length() <= overlapChars) return "";
        String tail = prev.substring(prev.length() - overlapChars);
        if (separators != null && !separators.isEmpty()) {
            for (int i = 0; i < tail.length() - 1; i++) {
                if (separators.indexOf(tail.charAt(i)) >= 0) {
                    return tail.substring(i + 1);
                }
            }
        }
        return tail;
    }

    /**
     * 按传入的句读集合切分，保留分隔符；无句读的长段按 {@link #MAX_BODY_LEN} 硬切。
     *
     * <p>分隔符集合由调用方传入（KB={@link #KB_SEPARATORS}、NEWS={@link #NEWS_SEPARATORS}），
     * 以保持两域改造前的切块边界逐块一致（AC4）。空/空白集合视为无句读（整段硬切）。
     */
    static List<String> splitSentences(String p, String separators) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            cur.append(ch);
            if (separators != null && separators.indexOf(ch) >= 0) {
                String s = cur.toString();
                if (!s.isBlank()) out.add(s);
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            String tail = cur.toString();
            if (tail.length() <= MAX_BODY_LEN) {
                if (!tail.isBlank()) out.add(tail);
            } else {
                // 无句读的超长尾巴硬切
                for (int i = 0; i < tail.length(); i += MAX_BODY_LEN) {
                    out.add(tail.substring(i, Math.min(tail.length(), i + MAX_BODY_LEN)));
                }
            }
        }
        return out;
    }
}
