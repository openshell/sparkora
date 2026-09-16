package com.sparkora.article.illustrate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配图锚点切分器（09-15 article-auto-illustrate，子C）。
 *
 * 从版本正文 Markdown 中切出**可配图的语义锚点**，供逐锚点语义检索图片（子B {@code searchImages}）。
 * 纯静态、无 Spring 依赖、不调 AI，可单测。
 *
 * 切分规则：
 * <ul>
 *   <li>按 ATX 标题分段：{@code ##}/{@code ###+} 各自起一段，段内空行分段落；
 *       标题前的前言也算一段（headingPath 为空）；{@code #} H1 视为文章标题（不产生锚点、不计入正文）。</li>
 *   <li>无标题时（罕见）退化为按空行切分段落，每段一个锚点。</li>
 *   <li>跳过：代码块（``` 围栏）、引用块行（{@code >} 开头）、纯列表段落、图片段落（剥标记后为空）、
 *       去空白后 &lt; {@value #MIN_TEXT_LEN} 字的过短段落。</li>
 *   <li>上限 {@code maxAnchors} 保序截断（优先靠前段落，避免配图过密）。</li>
 * </ul>
 */
public final class AnchorExtractor {

    /** 过短段落阈值：剔除 markdown 标记后去空白不足该长度的段落不参与配图建议。 */
    public static final int MIN_TEXT_LEN = 30;

    /** 锚点指纹取正文归一化后的前 N 字符。 */
    private static final int FINGERPRINT_TEXT_LEN = 80;

    /** 锚点指纹的 sha256 前缀长度（hex 字符数）。 */
    private static final int FINGERPRINT_HEX_LEN = 12;

    /** ATX 标题：（1~6 个 #）+ 空白 + 标题文本。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** 代码围栏（``` 或 ~~~，允许前置空白）。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
    /** 引用块行。 */
    private static final Pattern QUOTE = Pattern.compile("^\\s*>.*$");
    /** 列表项标记（bullet 列表 或 有序列表）。 */
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+.*$");
    /** 水平分割线。 */
    private static final Pattern HR = Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    /** 图片语法。 */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    /** 链接语法（保留链接文字）。 */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    /** 加粗（**x** / __x__）。 */
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*([^*]*)\\*\\*");
    private static final Pattern BOLD_UNDER = Pattern.compile("__([^_]*)__");
    /** 行内代码。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    /** 任意空白（指纹归一化用）。 */
    private static final Pattern ANY_WHITESPACE = Pattern.compile("\\s+");

    private AnchorExtractor() {
    }

    /**
     * 可配图锚点。
     *
     * @param anchorIndex 锚点在**返回列表**中的序号（0 起，保序）
     * @param headingPath 标题路径（如「续航实测」或「续航实测 > 高速工况」；前言段为空串，非 null）
     * @param text        锚点正文（已剔除 markdown 标记，单空格连接，trim）
     * @param key         锚点指纹（见 {@link #fingerprint}），用于「忽略」记录的稳定定位
     */
    public record Anchor(int anchorIndex, String headingPath, String text, String key) {
    }

    /** 原始分段：标题路径 + 该段的行（不含标题行本身）。 */
    private record RawSection(List<String> headingPath, List<String> lines) {
    }

    /**
     * 正文 Markdown → 可配图锚点（保序，受上限约束）。
     *
     * @param contentMd  正文 Markdown（null/空白 → 空列表）
     * @param maxAnchors 锚点上限（&lt;=0 → 空列表）；超出按顺序取前 N 个
     */
    public static List<Anchor> extract(String contentMd, int maxAnchors) {
        if (contentMd == null || contentMd.isBlank() || maxAnchors <= 0) return List.of();

        List<RawSection> sections = splitSections(contentMd);
        List<Anchor> anchors = new ArrayList<>();
        for (RawSection s : sections) {
            String text = extractText(s.lines());
            if (text == null) continue;   // 该段无可配图内容（纯列表/引用/过短/空）
            String headingPath = String.join(" > ", s.headingPath());
            anchors.add(new Anchor(anchors.size(), headingPath, text, fingerprint(headingPath, text)));
            if (anchors.size() >= maxAnchors) break;
        }
        return anchors;
    }

    /**
     * 锚点指纹：headingPath + 正文归一化前 {@value #FINGERPRINT_TEXT_LEN} 字符 → sha256 → 前 {@value #FINGERPRINT_HEX_LEN} 位 hex。
     *
     * 用指纹而非序号：正文编辑后序号会漂移，「忽略」记录会错位到别的段落；
     * 指纹在正文未编辑时稳定，编辑后仅该锚点失效（不误伤其他锚点）。
     * 归一化 = 剔除 markdown 标记 + 去掉全部空白 —— 纯格式调整（加粗、换行）不改变指纹。
     */
    public static String fingerprint(String headingPath, String text) {
        String norm = normalizeForFingerprint(text);
        String head = norm.length() > FINGERPRINT_TEXT_LEN ? norm.substring(0, FINGERPRINT_TEXT_LEN) : norm;
        String material = (headingPath == null ? "" : headingPath.trim()) + "\n" + head;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, FINGERPRINT_HEX_LEN);
        } catch (Exception e) {
            // SHA-256 必然存在；兜底用 hashCode 保证调用方（忽略记录）不因异常中断
            return String.format("%012x", material.hashCode() & 0xffffffffL);
        }
    }

    // ==================== 分段 ====================

    /**
     * 按 ATX 标题切段（代码围栏内的 {#} 不算标题）。
     * 标题前的前言为一段（headingPath 为空）；H1 视为文章标题不建段、不计入正文。
     */
    private static List<RawSection> splitSections(String contentMd) {
        String[] lines = contentMd.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<RawSection> out = new ArrayList<>();
        List<String> currentPath = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        boolean inFence = false;
        boolean sawSectionHeading = false;

        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                currentLines.add(line);
                continue;
            }
            Matcher m = inFence ? null : HEADING.matcher(line);
            if (m != null && m.matches()) {
                out.add(new RawSection(List.copyOf(currentPath), currentLines));
                int level = m.group(1).length();
                String title = m.group(2).trim();
                if (level == 2) {
                    sawSectionHeading = true;
                    currentPath = new ArrayList<>(List.of(title));
                } else if (level >= 3) {
                    sawSectionHeading = true;
                    // 三级及以下：挂到最近的二级标题下；无二级标题则自成路径
                    List<String> p = new ArrayList<>();
                    if (!currentPath.isEmpty()) p.add(currentPath.get(0));
                    p.add(title);
                    currentPath = p;
                } else {
                    // H1 = 文章标题：不算锚点标题，也不计入正文
                    currentPath = new ArrayList<>();
                }
                currentLines = new ArrayList<>();
                continue;
            }
            currentLines.add(line);
        }
        out.add(new RawSection(List.copyOf(currentPath), currentLines));

        // 无任何 ##/### 标题时退化为「按空行切分段落，每段一个锚点」（H1 标题行不计入正文）
        if (sawSectionHeading) return out;
        return splitByBlankLines(lines);
    }

    /** 无标题退化路径：整篇按空行切段落（代码围栏内空行不切；H1 标题行丢弃）。 */
    private static List<RawSection> splitByBlankLines(String[] lines) {
        List<RawSection> out = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        boolean inFence = false;
        for (String line : lines) {
            if (FENCE.matcher(line).matches()) inFence = !inFence;
            Matcher m = inFence ? null : HEADING.matcher(line);
            if (m != null && m.matches()) continue;      // 标题行不计入正文
            if (!inFence && line.isBlank()) {
                if (!buf.isEmpty()) {
                    out.add(new RawSection(List.of(), List.copyOf(buf)));
                    buf = new ArrayList<>();
                }
                continue;
            }
            buf.add(line);
        }
        if (!buf.isEmpty()) out.add(new RawSection(List.of(), List.copyOf(buf)));
        return out;
    }

    // ==================== 段内清洗 ====================

    /**
     * 段内行 → 可配图正文；无可配图内容返回 null。
     * 逐「段落」（空行分隔）过滤：代码块 / 引用块 / 纯列表 / 过短 / 剥标记后为空 均跳过，
     * 其余段落剥 markdown 标记后单空格连接。
     */
    private static String extractText(List<String> lines) {
        // 段落 = 空行分隔的行组（保留行边界，供 isPureList 判定）
        List<List<String>> paragraphs = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        boolean inFence = false;

        for (String line : lines) {
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;                        // 代码块内容整体丢弃
            if (QUOTE.matcher(line).matches()) continue;  // 引用块行丢弃（不需要配图）
            if (HR.matcher(line).matches()) continue;     // 分割线丢弃
            if (line.isBlank()) {
                if (!buf.isEmpty()) {
                    paragraphs.add(List.copyOf(buf));
                    buf = new ArrayList<>();
                }
                continue;
            }
            buf.add(line.trim());
        }
        if (!buf.isEmpty()) paragraphs.add(List.copyOf(buf));

        List<String> kept = new ArrayList<>();
        for (List<String> p : paragraphs) {
            if (isPureList(p)) continue;
            String cleaned = stripMarkdown(String.join(" ", p));
            if (cleaned.isBlank()) continue;                       // 图片/链接-only 段（避免给配图建议区自己推荐）
            if (cleaned.replaceAll("\\s", "").length() < MIN_TEXT_LEN) continue;
            kept.add(cleaned);
        }
        if (kept.isEmpty()) return null;
        return String.join(" ", kept).trim();
    }

    /** 纯列表段落：非空行**全部**是列表项（`-`/`*`/`+`/`1.` 开头），无普通句式。 */
    private static boolean isPureList(List<String> paragraphLines) {
        boolean any = false;
        for (String line : paragraphLines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!LIST_MARKER.matcher(t).matches()) return false;
            any = true;
        }
        return any;
    }

    /**
     * 剔除 markdown 标记（保留可读文字）：标题符号、图片语法、加粗、行内代码、链接（保留链接文字）、
     * 列表/引用标记、转义符，并压缩空白。
     */
    static String stripMarkdown(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = IMAGE.matcher(s).replaceAll("");                       // 图片整体丢弃
        s = LINK.matcher(s).replaceAll("$1");                      // 链接保留文字
        s = BOLD_STAR.matcher(s).replaceAll("$1");
        s = BOLD_UNDER.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");           // 残留标题符号
        s = s.replaceAll("(?m)^\\s{0,3}>\\s?", "");                // 残留引用符号
        s = s.replaceAll("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+", "");   // 列表标记
        s = s.replace("\\*", "*").replace("\\_", "_").replace("\\`", "`");
        return ANY_WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** 指纹归一化：剥标记 + 去全部空白（纯格式调整不影响指纹）。 */
    private static String normalizeForFingerprint(String text) {
        return stripMarkdown(text).replaceAll("\\s", "");
    }
}
