package com.sparkora.source.client;

import java.util.List;

/**
 * 采集列表条目(10-05-source-crawl-base)。规范化前的源内原始条目。
 *
 * @param externalId  源内唯一键(官方 id / 规范化 URL)
 * @param title       标题
 * @param url         详情链接(相对或绝对;由采集侧按 detail_base_url 解析)
 * @param publishDate 发布日期原文(可空;由采集侧尽力解析)
 * @param tags        源内标签(可空)
 * @param summary     源内自带正文/摘要(RSS {@code description}/{@code content:encoded};SITE 为 null)
 */
public record SourceItem(String externalId, String title, String url, String publishDate, List<String> tags,
                         String summary) {

    /** 兼容旧 5 参调用(summary=null)。 */
    public SourceItem(String externalId, String title, String url, String publishDate, List<String> tags) {
        this(externalId, title, url, publishDate, tags, null);
    }
}
