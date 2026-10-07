package com.sparkora.source.client;

import com.sparkora.domain.entity.SourceChannelEntity;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RSS/Atom 采集客户端(10-05-source-crawl-base)。jsoup {@code Parser.xmlParser()} 解析,<b>不新增依赖</b>。
 *
 * <p>兼容 RSS 2.0({@code item}/{@code pubDate}/{@code guid})与 Atom({@code entry}/{@code published}/{@code id})。
 * 日期缺失/格式异常一律容错为 null,由编排层尽力解析。解析失败返回空列表,不抛。
 */
@Slf4j
@Component
public class RssSourceClient implements SourceClient {

    @Override
    public String type() {
        return "RSS";
    }

    @Override
    public List<SourceItem> list(String html, SourceChannelEntity channel) {
        if (html == null || html.isBlank()) return List.of();
        List<SourceItem> out = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html, "", Parser.xmlParser());
            Elements entries = doc.select("item");
            if (entries.isEmpty()) entries = doc.select("entry");   // Atom
            for (Element e : entries) {
                String link = linkOf(e);
                String externalId = firstNonBlank(text(e, "guid"), text(e, "id"), link);
                if (externalId == null) continue;
                String title = text(e, "title");
                String date = firstNonBlank(text(e, "pubDate"), text(e, "published"), text(e, "updated"), text(e, "dc:date"));
                String summary = contentOf(e);
                out.add(new SourceItem(externalId, title, link, date, categories(e), summary));
            }
        } catch (Exception ex) {
            log.warn("RSS 解析失败,降级为空列表: {}", ex.getMessage());
            return List.of();
        }
        return out;
    }

    @Override
    public SourceContent detail(String html, SourceChannelEntity channel, SourceItem item) {
        // RSS 通常已含正文/摘要(description/content:encoded);feed 内即正文,不再依赖二次抓取。
        if (item == null || item.summary() == null || item.summary().isBlank()) return SourceContent.empty();
        try {
            String text = Jsoup.parse(item.summary()).text();
            return new SourceContent(text == null ? "" : text.trim(), item.summary(), List.of());
        } catch (Exception e) {
            return new SourceContent(item.summary(), item.summary(), List.of());
        }
    }

    /** 取条目链接:RSS {@code <link>text</link>};Atom {@code <link href="..."/>}。 */
    private static String linkOf(Element e) {
        Element link = e.selectFirst("link");
        if (link == null) return null;
        String href = link.attr("href");
        if (href != null && !href.isBlank()) return href.trim();
        String text = link.text().trim();
        return text.isEmpty() ? null : text;
    }

    /** 取条目自带正文/摘要:优先 {@code content:encoded}(全文),否则 {@code description}/{@code summary}/{@code content}。 */
    private static String contentOf(Element e) {
        return firstNonBlank(text(e, "encoded"), text(e, "content:encoded"), text(e, "description"),
                text(e, "summary"), text(e, "content"));
    }

    /** 取条目分类标签(可多个 {@code <category>},RSS 取 text、Atom 取 term)。 */
    private static List<String> categories(Element e) {
        Elements cats = e.select("category");
        if (cats.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Element c : cats) {
            String v = firstNonBlank(c.text(), c.attr("term"));
            if (v != null) out.add(v.trim());
        }
        return out;
    }

    private static String text(Element e, String tag) {
        // 用 getElementsByTag(而非 selectFirst)以兼容 namespaced 标签(如 dc:date,jsoup 查询语法不接受冒号)
        Elements els = e.getElementsByTag(tag);
        return els.isEmpty() ? null : blankToNull(els.first().text());
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
