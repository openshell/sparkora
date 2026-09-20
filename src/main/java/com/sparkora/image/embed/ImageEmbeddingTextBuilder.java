package com.sparkora.image.embed;

import com.sparkora.domain.entity.ImageAssetEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片嵌入文本构造器（09-15 img-semantic-search，子B）。
 *
 * 纯静态、无 Spring 依赖、零 AI 调用（可单测）：图片本身没有可嵌入的文本，
 * 必须用「描述性文本代理」——把来源新闻标题、图片标签、AI prompt、文件名等既有信号拼成一段文本，
 * 交给统一的 EmbeddingClient（Qwen3-Embedding-8B / 1024 维）向量化。
 *
 * 按 {@code source} 分派（契约见 docs/spec/image.md「图片语义检索」）：
 * <ul>
 *   <li>{@code byd-news}：来源新闻标题（优先，由 source_ref 反查）+ 标签（含 主题/*、年份/*）；</li>
 *   <li>{@code ai-text2img} / {@code ai-img2img}：promptText + 标签；</li>
 *   <li>{@code upload}：文件名（去扩展名）+ 标签；</li>
 *   <li>{@code byd}（车型图）：文件名（去扩展名）+ 标签（含 车型-*）。</li>
 * </ul>
 *
 * 边界：各段空格连接、去空段；整体 trim 后为空时兜底 {@code (图片 <id>)}——**仍写向量**，
 * 避免图库里出现「搜不到」的缺向量图（检索侧由相似度门槛过滤低质命中）。
 * 标签**原样拼**（保留 {@code 主题/} 前缀）：「销量」等关键词本身就是检索信号，剥前缀反而丢信息。
 * 长度上限 {@link #MAX_TEXT_LEN} 字符截断，防超长输入打爆 embedding 服务（标题+标签实际远小于此）。
 */
public final class ImageEmbeddingTextBuilder {

    /** 嵌入文本长度上限（字符）：防超长输入；正常「标题 + 标签」远小于此值。 */
    public static final int MAX_TEXT_LEN = 2000;

    private ImageEmbeddingTextBuilder() {
    }

    /**
     * 构造单图的嵌入文本（纯函数）。
     *
     * @param img       图库实体（读 source/fileName/promptText/id；为 null 时返回空兜底文本）
     * @param tags      该图已有标签名（可 null/含空项；未 normalize 也能处理）
     * @param newsTitle 来源新闻标题（仅 byd-news 且 source_ref 反查成功时非空；null 表示查不到，退化为只用标签）
     * @return 嵌入文本（非空；超长按 {@link #MAX_TEXT_LEN} 截断）
     */
    public static String build(ImageAssetEntity img, List<String> tags, String newsTitle) {
        if (img == null) return fallback(null);
        String source = img.getSource() == null ? "" : img.getSource().trim();

        List<String> parts = new ArrayList<>();
        switch (source) {
            case "byd-news" -> {
                // 新闻图：标题是信息量最大的信号（主题分类词也来自标题），标签补充结构化维度
                addIfPresent(parts, newsTitle);
                addAllIfPresent(parts, tags);
            }
            case "ai-text2img", "ai-img2img" -> {
                // AI 生成图：prompt 即用户对画面内容的描述，最贴近语义检索意图
                addIfPresent(parts, img.getPromptText());
                addAllIfPresent(parts, tags);
            }
            default -> {
                // upload / byd（车型图）/ 其他未知来源：文件名（去扩展名）+ 标签（车型图含「车型-<名>」）
                addIfPresent(parts, baseName(img.getFileName()));
                addAllIfPresent(parts, tags);
            }
        }

        String text = String.join(" ", parts).trim();
        if (text.isEmpty()) return fallback(img.getId());
        return text.length() > MAX_TEXT_LEN ? text.substring(0, MAX_TEXT_LEN) : text;
    }

    /** 兜底文本（各段全空时使用）：仍要写向量，否则该图在语义检索中永远不可见。 */
    static String fallback(Long imageId) {
        return "(图片 " + (imageId == null ? "" : imageId) + ")";
    }

    /** 单段追加：null/空白丢弃，否则 trim 后加入。 */
    private static void addIfPresent(List<String> parts, String value) {
        if (value == null) return;
        String v = value.trim();
        if (!v.isEmpty()) parts.add(v);
    }

    /** 标签段追加：逐项 trim + 去空（不在这里去重——嵌入文本里重复标签无实害，且去重属标签服务职责）。 */
    private static void addAllIfPresent(List<String> parts, List<String> values) {
        if (values == null || values.isEmpty()) return;
        for (String v : values) addIfPresent(parts, v);
    }

    /** 文件名去扩展名（无扩展名/空值原样返回）。 */
    static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
