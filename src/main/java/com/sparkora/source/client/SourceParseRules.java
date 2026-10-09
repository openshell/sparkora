package com.sparkora.source.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 栏目解析规则(10-05-source-crawl-base;10-09-cpca-gasgoo-collection 扩展)。对应 {@code sparkora_source_channel.parse_rules} 的 JSON。
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
 *   "listRows": "div.data ul li",  // 正文内列表型结构化行选择器(10-09;如盖世排行榜)
 *   "rowCells": "span",            // 列表行内单元格选择器(10-09;缺省取整行文本)
 *   "images": "img",               // 正文内图片选择器(配图转存用)
 *   "imageDeny": "/common/,qrcode",// 图片 URL 子串黑名单(逗号分隔;叠加内置默认)
 *   "imageAllow": "moblogo/News/", // 图片 URL 子串白名单(可选;非空则须命中其一)
 *   "bydOnly": false               // true=只保留标题含 BYD/比亚迪 的条目(工信部申报等)
 * }
 * </pre>
 *
 * <p>{@code listRows}/{@code rowCells}/{@code imageDeny}/{@code imageAllow} 为 10-09 增量字段,均可空;
 * 未配置时行为与既有逐字一致(零回归)。
 */
public record SourceParseRules(
        String listSelector,
        String itemLinkSelector,
        String itemTitleSelector,
        String itemDateSelector,
        String contentSelector,
        String tableSelector,
        String imageSelector,
        boolean bydOnly,
        String listRowsSelector,
        String rowCellsSelector,
        String imageDeny,
        String imageAllow) {

    /**
     * 兼容构造器(保留既有 8 参调用):新增字段默认 null,行为不变。
     */
    public SourceParseRules(String listSelector, String itemLinkSelector, String itemTitleSelector,
                            String itemDateSelector, String contentSelector, String tableSelector,
                            String imageSelector, boolean bydOnly) {
        this(listSelector, itemLinkSelector, itemTitleSelector, itemDateSelector, contentSelector,
                tableSelector, imageSelector, bydOnly, null, null, null, null);
    }

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
                    n.path("bydOnly").asBoolean(false),
                    text(n, "listRows"), text(n, "rowCells"), text(n, "imageDeny"), text(n, "imageAllow"));
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
