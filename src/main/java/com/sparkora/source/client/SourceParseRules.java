package com.sparkora.source.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 栏目解析规则(10-05-source-crawl-base)。对应 {@code sparkora_source_channel.parse_rules} 的 JSON。
 *
 * <p>选择器集中于此(仿 {@code NewsContentParser}:站点改版只改一条数据,不做通用智能抽取)。
 * JSON 示例:
 * <pre>
 * {
 *   "list": ".news-list li",       // 列表条目容器选择器
 *   "link": "a",                   // 条目内链接选择器(取 href)
 *   "title": "a",                  // 条目内标题选择器
 *   "date": ".date",               // 条目内日期选择器(可选;缺省取详情日期)
 *   "detail": ".article-content",  // 详情正文容器选择器
 *   "tables": "table",             // 正文内表格选择器(转保留行列文本)
 *   "images": "img",               // 正文内图片选择器(配图转存用)
 *   "bydOnly": false               // true=只保留标题含 BYD/比亚迪的条目(工信部申报等)
 * }
 * </pre>
 */
public record SourceParseRules(
        String listSelector,
        String itemLinkSelector,
        String itemTitleSelector,
        String itemDateSelector,
        String contentSelector,
        String tableSelector,
        String imageSelector,
        boolean bydOnly) {

    /** 空规则(全部走默认选择器)。 */
    public static final SourceParseRules EMPTY =
            new SourceParseRules(null, null, null, null, null, null, null, false);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 解析 JSON;null/空白/非法均降级为 {@link #EMPTY},不抛。 */
    public static SourceParseRules parse(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY;
        try {
            JsonNode n = JSON.readTree(raw);
            return new SourceParseRules(
                    text(n, "list"), text(n, "link"), text(n, "title"), text(n, "date"),
                    text(n, "detail"), text(n, "tables"), text(n, "images"),
                    n.path("bydOnly").asBoolean(false));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }
}
