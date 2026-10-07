package com.sparkora.source.client;

import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.source.service.SourceTableParser;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SITE 采集客户端(10-05-source-crawl-base)。列表页选择器定位条目、详情选择器抽正文。
 *
 * <p>选择器来自栏目 {@code parse_rules}(集中配置,仿 {@code NewsContentParser}),站点改版只改一条记录。
 * 详情正文中的 HTML 表格先经 {@link SourceTableParser} 转保留行列的逐行文本(父 design §6),
 * 避免落入 {@code TextChunker}「段内换行转空格」被压平。解析失败降级空内容,不抛。
 */
@Slf4j
@Component
public class SiteSourceClient implements SourceClient {

    @Override
    public String type() {
        return "SITE";
    }

    @Override
    public List<SourceItem> list(String html, SourceChannelEntity channel) {
        if (html == null || html.isBlank()) return List.of();
        SourceParseRules rules = SourceParseRules.parse(channel == null ? null : channel.getParseRules());
        if (rules.listSelector() == null) return List.of();
        List<SourceItem> out = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html, baseUrlOf(channel));
            Elements items = doc.select(rules.listSelector());
            for (Element it : items) {
                Element linkEl = itemLinkSelector(rules) == null ? it.selectFirst("a") : it.selectFirst(itemLinkSelector(rules));
                if (linkEl == null) continue;
                String href = firstNonBlank(linkEl.attr("href"), linkEl.attr("data-href"));
                if (href == null) continue;
                String abs = linkEl.absUrl("href");
                String url = (abs == null || abs.isBlank()) ? href : abs;
                Element titleEl = rules.itemTitleSelector() == null ? linkEl : it.selectFirst(rules.itemTitleSelector());
                String title = titleEl == null ? linkEl.text() : titleEl.text();
                String date = rules.itemDateSelector() == null ? null : textOf(it.selectFirst(rules.itemDateSelector()));
                SourceItem item = new SourceItem(url, blankToNull(title), url, date, List.of());
                if (accept(item, rules)) out.add(item);
            }
        } catch (Exception e) {
            log.warn("SITE 列表解析失败,降级为空列表: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    @Override
    public SourceContent detail(String html, SourceChannelEntity channel, SourceItem item) {
        if (html == null || html.isBlank()) return SourceContent.empty();
        SourceParseRules rules = SourceParseRules.parse(channel == null ? null : channel.getParseRules());
        try {
            Document doc = Jsoup.parse(html, baseUrlOf(channel));
            Element container = rules.contentSelector() == null ? doc.body() : doc.selectFirst(rules.contentSelector());
            if (container == null) container = doc.body();

            List<String> blocks = new ArrayList<>();
            // 表格先转行文本,整体并入正文(保留行列语义)
            for (String t : SourceTableParser.parseTables(container, rules.tableSelector())) blocks.add(t);
            // 段落文本
            Elements ps = container.select("p");
            if (!ps.isEmpty()) {
                for (Element p : ps) {
                    String t = p.text().replace('\u00a0', ' ').trim();
                    if (!t.isEmpty()) blocks.add(t);
                }
            } else {
                // 无 <p> 容器(如纯 div):取容器整体文本兜底,避免空正文
                String t = container.text().replace('\u00a0', ' ').trim();
                if (!t.isEmpty()) blocks.add(t);
            }
            String content = String.join("\n\n", blocks);

            // 正文图片抽取(配图转存用;保留原始链,基址解析由 SourceImageService 负责)
            List<String> images = new ArrayList<>();
            Elements imgs = rules.imageSelector() == null ? container.select("img") : container.select(rules.imageSelector());
            for (Element img : imgs) {
                String src = firstNonBlank(img.attr("src"), img.attr("data-src"), img.attr("data-original"));
                if (src != null) images.add(src.trim());
            }
            return new SourceContent(content, html, images);
        } catch (Exception e) {
            log.warn("SITE 详情解析失败,降级为空正文: {}", e.getMessage());
            return SourceContent.empty();
        }
    }

    /** BYD 优先过滤(工信部申报等):命中规则时只保留标题含 BYD/比亚迪的条目。 */
    static boolean accept(SourceItem item, SourceParseRules rules) {
        if (!rules.bydOnly()) return true;
        String title = item.title() == null ? "" : item.title().toUpperCase(java.util.Locale.ROOT);
        String url = item.url() == null ? "" : item.url().toUpperCase(java.util.Locale.ROOT);
        return title.contains("BYD") || title.contains("比亚迪") || url.contains("BYD");
    }

    private static String itemLinkSelector(SourceParseRules rules) {
        return rules.itemLinkSelector();
    }

    private static String baseUrlOf(SourceChannelEntity channel) {
        return channel == null ? "" : (channel.getDetailBaseUrl() == null ? "" : channel.getDetailBaseUrl());
    }

    private static String textOf(Element e) {
        return e == null ? null : blankToNull(e.text());
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
