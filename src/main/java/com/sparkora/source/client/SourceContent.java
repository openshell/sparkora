package com.sparkora.source.client;

import java.util.List;

/**
 * 采集详情内容(10-05-source-crawl-base)。
 *
 * @param text       正文纯文本(表格已转保留行列的逐行文本,见 {@code SourceTableParser})
 * @param html       原始 HTML(留痕/图片抽取用;可空)
 * @param imageUrls  正文图片原始链(相对或绝对,未经基址解析;可空)
 */
public record SourceContent(String text, String html, List<String> imageUrls) {

    public static SourceContent empty() {
        return new SourceContent("", null, List.of());
    }
}
