package com.sparkora.qa.service;

import java.util.List;

/**
 * 图片意图判定（09-15 qa-auto-illustrate，子D 语义路径）。
 *
 * 「给我看销量海报」这类问法要触发图片语义检索；普通知识提问（如「海狮08续航多少」）
 * **不得**触发——否则每条问答都多一次 embedding 调用（浪费且拖慢）。
 *
 * <h3>为什么用关键词而非 LLM</h3>
 * 零成本、可单测、可解释；误判的代价仅是「多显示几张缩略图」（不写用户内容、不阻断答案），
 * 远小于引入一次 LLM 判定的延迟与不确定性。
 *
 * 纯静态无 Spring 依赖（可单测，参照 {@code NewsImageClassifier}/{@code AnchorExtractor} 先例）。
 */
public final class QaImageIntent {

    private QaImageIntent() {}

    /**
     * 图片意图关键词（命中任一即视为图片意图）。
     * **长的排前**：{@link #cleanQuery} 按序 replace，先剥短词会把长词切碎（「看图片」被「看图」剥成「片」），
     * 故「看图片」必须排在「看图」之前。{@link #isImageIntent} 的 contains 判定与顺序无关。
     */
    static final List<String> INTENT_WORDS = List.of(
            "看图片", "看张图", "看照片", "看图吧", "给我看", "我想看", "看一下",
            "看图", "图片", "海报", "照片", "配图");

    /** 问题是否表达「要图片」的意图（null/空/空白 → false）。 */
    public static boolean isImageIntent(String question) {
        if (question == null || question.isBlank()) return false;
        for (String w : INTENT_WORDS) {
            if (question.contains(w)) return true;
        }
        return false;
    }

    /**
     * 语义检索 query 清洗：剥离意图短语后若剩余为空，则回退用原问题。
     *
     * 例：「给我看比亚迪销量海报」→「比亚迪销量」（检索信号更纯）；
     * 「给我看图」→ 剥离后为空 → 用原问题（虽然语义检索信号弱，但不至于空 query 报错）。
     */
    public static String cleanQuery(String question) {
        if (question == null) return "";
        String q = question;
        for (String w : INTENT_WORDS) {
            q = q.replace(w, " ");
        }
        q = q.replaceAll("\\s+", " ").trim();
        return q.isEmpty() ? question.trim() : q;
    }
}
