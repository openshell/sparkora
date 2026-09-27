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
 */
public final class TextChunker {

    /** 单块正文目标上限（不含首行标题）。 */
    public static final int MAX_BODY_LEN = 500;

    /** KB（通用知识）历史句读集合：{@code 。；!?}（保持改造前逐块一致，不扩集）。 */
    public static final String KB_SEPARATORS = "。；!?";

    /** NEWS（官方新闻）历史句读集合：{@code 。；;！!？?}（KB 的超集，另含全角！？与半角;）。 */
    public static final String NEWS_SEPARATORS = "。；;！!？?";

    private TextChunker() {
    }

    /**
     * 切块。
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
        List<String> out = new ArrayList<>();
        // 兜底:正文无有效字符(全空白)时按各域空正文语义处理
        if (content == null || content.strip().isEmpty()) {
            if (keepTitleWhenEmpty || titlePresent) out.add(header);
            return out;
        }
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
        for (String b : bodies) {
            out.add(header + "\n" + b);
        }
        if (out.isEmpty()) out.add(header);   // 双保险:正文全为符号等极端情况
        return out;
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
