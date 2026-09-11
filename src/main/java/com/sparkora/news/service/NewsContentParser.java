package com.sparkora.news.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新闻详情页 SSR HTML 正文抽取(C2,jsoup)。
 *
 * 选择器(集中在本文,官网结构变更只改这里):
 *  - 标题:.cmp-news__detail-title
 *  - 日期:.cmp-news__detail-date(如「发布于 2026-09-01 17:08:31」)
 *  - 正文容器:.cmp-news__detail-content
 *      · 段落 .news-text p(<br> 转换行)
 *      · 图片 .news-image img(仅记录 src,不下载;以「[图片] <src>」行并入正文)
 *
 * 容错:解析异常/选择器无命中返回空正文(不抛);图片型新闻正文可能为空,仍入库元数据,切块跳过。
 */
@Slf4j
public final class NewsContentParser {

    /** 从日期文本中提取「YYYY-MM-DD HH:mm:ss」或「YYYY-MM-DD」片段。 */
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{2}:\\d{2}(?::\\d{2})?))?");

    private NewsContentParser() {}

    /** 抽取结果。publishDate 为原始日期文本(形如 2026-09-01 17:08:31),由调用方解析为 LocalDateTime。 */
    public record Parsed(String title, String content, String publishDate) {}

    /** 解析详情页 HTML。任何异常均降级为空结果,不抛出。 */
    public static Parsed parse(String html) {
        if (html == null || html.isBlank()) return new Parsed(null, "", null);
        try {
            Document doc = Jsoup.parse(html);
            String title = textOf(doc, ".cmp-news__detail-title");
            String publishDate = extractDate(textOf(doc, ".cmp-news__detail-date"));

            Element container = doc.selectFirst(".cmp-news__detail-content");
            List<String> blocks = new ArrayList<>();
            if (container != null) {
                // 段落:<br> 转文本换行,保留段内换行;空段跳过
                for (Element p : container.select(".news-text p")) {
                    p.select("br").forEach(br -> br.replaceWith(new TextNode("\n")));
                    String text = p.wholeText().replace('\u00a0', ' ').strip();
                    if (!text.isEmpty()) blocks.add(text);
                }
                // 图片:仅记录 src(不下载),以标记行并入正文
                Elements imgs = container.select(".news-image img");
                for (Element img : imgs) {
                    String src = img.attr("src");
                    if (src == null || src.isBlank()) src = img.attr("data-src");
                    if (src != null && !src.isBlank()) blocks.add("[图片] " + src.strip());
                }
            }
            String content = String.join("\n\n", blocks);
            return new Parsed(title, content, publishDate);
        } catch (Exception e) {
            log.warn("新闻详情页解析失败,降级为空正文: {}", e.getMessage());
            return new Parsed(null, "", null);
        }
    }

    private static String textOf(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        if (el == null) return null;
        String t = el.text().strip();
        return t.isEmpty() ? null : t;
    }

    /** 从「发布于 2026-09-01 17:08:31」提取「2026-09-01 17:08:31」;无匹配返回 null。 */
    static String extractDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = DATE_PATTERN.matcher(raw);
        if (!m.find()) return null;
        String date = m.group(1);
        String time = m.group(2);
        return time == null ? date + " 00:00:00" : date + " " + (time.length() == 5 ? time + ":00" : time);
    }
}
