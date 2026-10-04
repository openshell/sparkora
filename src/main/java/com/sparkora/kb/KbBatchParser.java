package com.sparkora.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KB 批量导入解析器(10-03 C 批量导入)。
 *
 * <p>纯静态、无 Spring 依赖、可单测:把 CSV / JSON / Markdown 三种来源统一解析为
 * {@link KbImportRow} 列表,交由 {@code KbBatchImportService} 逐条委托
 * {@code KbDocService.create}。
 *
 * <p>错误边界:
 * <ul>
 *   <li>**整体解析失败**(CSV 缺表头/缺 title|content 列、JSON 非数组、无法识别格式)抛
 *       {@link IllegalArgumentException},由控制器映射 400,**先于任何写库**(不产生孤儿)。</li>
 *   <li>**行级失败**(如日期格式非法)写入 {@link KbImportRow#getParseError()},该行跳过写库但其余继续。</li>
 * </ul>
 *
 * <p>CSV 采用 RFC4180 子集自实现(引号包裹、{@code ""} 转义、内嵌逗号/换行),不引入重型 CSV 依赖。
 */
public final class KbBatchParser {

    /** CSV 表头列名(小写)。 */
    private static final String COL_TITLE = "title";
    private static final String COL_DOMAIN = "domain";
    private static final String COL_CONTENT = "content";
    private static final String COL_SOURCE = "source";
    private static final String COL_TAGS = "tags";
    private static final String COL_FROM = "effectivefrom";
    private static final String COL_TO = "effectiveto";

    private KbBatchParser() {
    }

    // ==================== 格式判定 ====================

    /**
     * 判定导入格式:文件后缀优先,缺失则按内容嗅探。
     *
     * @return {@code csv} / {@code json} / {@code markdown}
     */
    public static String detectFormat(String filename, String content) {
        String name = filename == null ? "" : filename.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return "csv";
        if (name.endsWith(".json")) return "json";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "markdown";

        String body = stripBom(content == null ? "" : content).stripLeading();
        if (body.startsWith("[") || body.startsWith("{")) return "json";
        String firstLine = firstNonBlankLine(body);
        if (firstLine.startsWith("#")) return "markdown";
        if (firstLine.contains(",")) return "csv";
        return "markdown";
    }

    /** 归一化显式传入的 format:支持 csv/json/md/markdown;其余抛 400 语义。 */
    public static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return null;
        String f = format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "csv" -> "csv";
            case "json" -> "json";
            case "md", "markdown" -> "markdown";
            default -> throw new IllegalArgumentException("不支持的导入格式: " + format + "(可选: csv/json/markdown)");
        };
    }

    // ==================== CSV ====================

    /**
     * 解析 CSV(首行表头必填)。缺 {@code title} 或 {@code content} 列 → 整体失败。
     */
    public static List<KbImportRow> parseCsv(String raw) {
        String content = stripBom(raw == null ? "" : raw);
        List<List<String>> records = parseCsvRecords(content);
        if (records.isEmpty()) throw new IllegalArgumentException("CSV 为空或缺少表头");
        List<String> header = records.get(0);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty() && !idx.containsKey(h)) idx.put(h, i);
        }
        if (!idx.containsKey(COL_TITLE) || !idx.containsKey(COL_CONTENT)) {
            throw new IllegalArgumentException("CSV 表头必须包含 title 与 content 列");
        }
        List<KbImportRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            // 全空白行(尾随换行产生的空记录)跳过,不计入 total
            if (rec.stream().allMatch(v -> v == null || v.isBlank())) continue;
            KbImportRow row = new KbImportRow();
            row.setTitle(cell(rec, idx, COL_TITLE));
            row.setDomain(cell(rec, idx, COL_DOMAIN));
            row.setContent(cell(rec, idx, COL_CONTENT));
            row.setSource(cell(rec, idx, COL_SOURCE));
            row.setTags(splitTags(cell(rec, idx, COL_TAGS)));
            applyDates(row, cell(rec, idx, COL_FROM), cell(rec, idx, COL_TO));
            rows.add(row);
        }
        return rows;
    }

    private static String cell(List<String> rec, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        if (i == null || i >= rec.size()) return null;
        String v = rec.get(i);
        return v == null ? null : v.trim();
    }

    /**
     * RFC4180 子集状态机:逐字符扫描,产出「记录 × 字段」。
     * 支持 {@code ""} 转义、引号字段内嵌逗号与换行、CRLF/LF/CR 记录分隔。
     */
    static List<List<String>> parseCsvRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int n = content.length();
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (field.length() == 0) {
                        inQuotes = true;
                        fieldStarted = true;
                    } else {
                        // 字段中途出现裸引号:宽松处理为字面量
                        field.append(c);
                    }
                }
                case ',' -> {
                    cur.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                }
                case '\r' -> {
                    cur.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    records.add(cur);
                    cur = new ArrayList<>();
                    if (i + 1 < n && content.charAt(i + 1) == '\n') i++;
                }
                case '\n' -> {
                    cur.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    records.add(cur);
                    cur = new ArrayList<>();
                }
                default -> {
                    field.append(c);
                    fieldStarted = true;
                }
            }
        }
        // 收尾:最后一行无换行,或引号未闭合时也要产出
        if (fieldStarted || field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            records.add(cur);
        }
        return records;
    }

    // ==================== JSON ====================

    /**
     * 解析 JSON 对象数组;字段名与 CSV 一致,{@code tags} 兼容数组/字符串。非数组 → 整体失败。
     */
    public static List<KbImportRow> parseJson(String raw, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(stripBom(raw == null ? "" : raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("JSON 根节点必须是对象数组");
        }
        List<KbImportRow> rows = new ArrayList<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("JSON 数组元素必须是对象");
            }
            KbImportRow row = new KbImportRow();
            row.setTitle(text(node, COL_TITLE));
            row.setDomain(text(node, COL_DOMAIN));
            row.setContent(text(node, COL_CONTENT));
            row.setSource(text(node, COL_SOURCE));
            row.setTags(tagsNode(field(node, COL_TAGS)));
            applyDates(row, text(node, COL_FROM), text(node, COL_TO));
            rows.add(row);
        }
        return rows;
    }

    /** 字段查找:精确名优先,否则按小写做大小写不敏感匹配(兼容 effectiveFrom / effectivefrom 等)。 */
    private static JsonNode field(JsonNode node, String lowerName) {
        JsonNode v = node.get(lowerName);
        if (v != null) return v;
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n != null && n.toLowerCase(Locale.ROOT).equals(lowerName)) return node.get(n);
        }
        return null;
    }

    private static String text(JsonNode node, String col) {
        JsonNode v = field(node, col);
        if (v == null || v.isNull()) return null;
        return v.isValueNode() ? v.asText() : v.toString();
    }

    /** tags 兼容 JSON 数组与分隔字符串;非字符串元素用 asText 归一。 */
    private static List<String> tagsNode(JsonNode v) {
        if (v == null || v.isNull()) return List.of();
        if (v.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode t : v) if (t != null && !t.isNull()) out.add(t.asText());
            return out;
        }
        if (v.isTextual()) return splitTags(v.asText());
        return List.of(v.asText());
    }

    // ==================== Markdown ====================

    /**
     * 按一级标题 {@code ^#\s+} 分段为多篇;无 H1 → 整文件一篇({@code fallbackTitle} 作标题)。
     */
    public static List<KbImportRow> parseMarkdown(String raw, String fallbackTitle) {
        String content = stripBom(raw == null ? "" : raw).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = content.split("\n", -1);
        List<KbImportRow> rows = new ArrayList<>();
        String curTitle = null;
        StringBuilder body = new StringBuilder();
        boolean sawH1 = false;
        for (String line : lines) {
            String h1 = h1Text(line);
            if (h1 != null) {
                if (sawH1 || body.length() > 0) flush(rows, curTitle, body, fallbackTitle);
                sawH1 = true;
                curTitle = h1;
                body.setLength(0);
            } else {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
        if (sawH1 || body.length() > 0) flush(rows, curTitle, body, fallbackTitle);
        if (rows.isEmpty()) {
            KbImportRow row = new KbImportRow();
            row.setTitle(fallbackTitle);
            row.setContent("");
            rows.add(row);
        }
        return rows;
    }

    private static void flush(List<KbImportRow> rows, String title, StringBuilder body, String fallbackTitle) {
        KbImportRow row = new KbImportRow();
        row.setTitle(title != null ? title : fallbackTitle);
        row.setContent(body.toString().strip());
        rows.add(row);
        body.setLength(0);
    }

    /** 命中一级标题则返回标题文本(去除 {@code #} 与首尾空白),否则 null。 */
    private static String h1Text(String line) {
        if (line == null) return null;
        String t = line.stripLeading();
        if (!t.startsWith("#")) return null;
        int i = 0;
        while (i < t.length() && t.charAt(i) == '#') i++;
        if (i != 1) return null;                 // 仅一级标题(单个 #)
        if (i >= t.length() || !Character.isWhitespace(t.charAt(i))) return null;
        return t.substring(i).trim();
    }

    // ==================== 公共工具 ====================

    /** 按 {@code ;} 拆分标签(trim/去空);空输入 → 空列表。 */
    static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * ISO {@code yyyy-MM-dd} 解析;空白 → null。格式非法抛
     * {@link IllegalArgumentException}(调用方转为行级失败,不整批中断)。
     */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式须为 yyyy-MM-dd: " + t);
        }
    }

    /** 解析生效期,失败写入行级 parseError(不抛整批)。 */
    private static void applyDates(KbImportRow row, String from, String to) {
        try {
            row.setEffectiveFrom(parseDate(from));
        } catch (IllegalArgumentException e) {
            row.setParseError("生效起" + e.getMessage());
            return;
        }
        try {
            row.setEffectiveTo(parseDate(to));
        } catch (IllegalArgumentException e) {
            row.setParseError("生效止" + e.getMessage());
        }
    }

    private static String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String firstNonBlankLine(String body) {
        for (String line : body.split("\n", -1)) {
            if (!line.isBlank()) return line.stripLeading();
        }
        return "";
    }
}
