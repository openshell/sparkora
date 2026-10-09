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
 * SITE 采集客户端(10-05-source-crawl-base;10-09-cpca-gasgoo-collection G2/G3/G5 扩展)。
 * 列表页选择器定位条目、详情选择器抽正文。
 *
 * <p>选择器来自栏目 {@code parse_rules}(集中配置,仿 {@code NewsContentParser}),站点改版只改一条记录。
 *
 * <ul>
 *   <li><b>G2 容器优先</b>:{@code detail} 命中容器时只在该容器内取正文(不回退 body)。容器内段落
 *       {@code <p>} 拼接达阈值用段落,否则取容器整段文本按块拆分——修复 {@code section>span} 型站点
 *       (乘联会)只认 {@code <p>} 取空的问题;无 {@code detail} 选择器时保持旧行为(零回归)。</li>
 *   <li><b>G3 结构化</b>:正文内 HTML 表格与列表型行({@code listRows}/{@code rowCells})均先转保留行列的
 *       逐行文本,避免落入 {@code TextChunker}「段内换行转空格」被压平。</li>
 *   <li><b>G5 配图过滤</b>:图片在正文容器内抽取,叠加内置默认 deny 常量与栏目 {@code imageDeny}/{@code imageAllow}
 *       子串过滤,剔除图标/logo/二维码。</li>
 * </ul>
 *
 * <p>解析失败降级空内容,不抛。
 */
@Slf4j
@Component
public class SiteSourceClient implements SourceClient {

    /** 段落拼接达此长度才认为「有正文段落」,否则改取容器整段文本(section>span 型站点)。 */
    static final int PARAGRAPH_MIN_CHARS = 60;

    /**
     * 图片 URL 内置默认 deny 子串(不依赖配置;栏目 {@code imageDeny} 可叠加)。
     *
     * <p>注意:不用裸 {@code logo}——盖世海报 CDN 为 {@code imagecn.gasgoo.com/moblogo/News/UEditor/...},
     * 裸 {@code logo} 会误杀全部海报(2026-10-09 实测)。改用 {@code companylogo} + {@code /logo/} 精确覆盖图标 logo。
     */
    static final List<String> DEFAULT_IMAGE_DENY =
            List.of("/common/", "companylogo", "/logo/", "qrcode", "160_110");

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
            log.warn("SITE 列表解析失败,降级为空列表: {}", e.getClass().getSimpleName());
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

            List<String> blocks = new ArrayList<>();
            List<String> images = new ArrayList<>();

            boolean containerDeclared = rules.contentSelector() != null;
            Element container = containerDeclared ? doc.selectFirst(rules.contentSelector()) : doc.body();
            if (container == null) container = doc.body();

            // 结构化先行(两条路径共用):表格 → 列表行(保留行列语义,数值不丢)
            for (String t : SourceTableParser.parseTables(container, rules.tableSelector())) blocks.add(t);
            for (String t : SourceTableParser.parseListRows(container, rules.listRowsSelector(), rules.rowCellsSelector())) {
                blocks.add(t);
            }

            if (!containerDeclared) {
                // G2 零回归:无详情容器选择器 → 沿用旧行为(段落优先/整段兜底)
                Elements ps = container.select("p");
                if (!ps.isEmpty()) {
                    for (Element p : ps) {
                        String t = normalize(p.text());
                        if (!t.isEmpty()) blocks.add(t);
                    }
                } else {
                    String t = normalize(container.text());
                    if (!t.isEmpty()) blocks.add(t);
                }
            } else {
                // G2 容器选择器优先;命中时只在该容器内取正文,不再回退 body(避免吞入导航/相关阅读)
                // 段落优先,不足阈值则取容器整段文本按块拆分(section>span 型站点)
                Elements ps = container.select("p");
                int pLen = 0;
                for (Element p : ps) pLen += normalize(p.text()).length();
                if (!ps.isEmpty() && pLen >= PARAGRAPH_MIN_CHARS) {
                    for (Element p : ps) {
                        String t = normalize(p.text());
                        if (!t.isEmpty()) blocks.add(t);
                    }
                } else {
                    for (String t : containerTextBlocks(container)) blocks.add(t);
                }
            }
            collectImages(container, rules, images);

            String content = String.join("\n\n", blocks);
            return new SourceContent(content, html, images);
        } catch (Exception e) {
            log.warn("SITE 详情解析失败,降级为空正文: {}", e.getClass().getSimpleName());
            return SourceContent.empty();
        }
    }

    /** 容器整段文本按块拆分(section/p/li/标题级),块之间用换行保留;跳过已被外层块覆盖的嵌套块,避免文本重复。 */
    private static List<String> containerTextBlocks(Element container) {
        List<String> out = new ArrayList<>();
        List<Element> chosen = new ArrayList<>();
        Elements candidates = container.select("p, section, li, h1, h2, h3, h4, blockquote");
        for (Element b : candidates) {
            boolean nested = false;
            for (Element c : chosen) {
                if (b.parents().contains(c)) {
                    nested = true;
                    break;
                }
            }
            if (nested) continue;
            String t = normalize(b.text());
            if (!t.isEmpty()) {
                out.add(t);
                chosen.add(b);
            }
        }
        if (out.isEmpty()) {
            String t = normalize(container.text());
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 正文图片抽取(G5):容器内选择器 + deny/allow 子串过滤;保留原始链(基址解析由 SourceImageService 负责)。 */
    private static void collectImages(Element container, SourceParseRules rules, List<String> out) {
        Elements imgs = rules.imageSelector() == null ? container.select("img") : container.select(rules.imageSelector());
        for (Element img : imgs) {
            String src = firstNonBlank(img.attr("src"), img.attr("data-src"), img.attr("data-original"));
            if (src != null && imageAllowed(src, rules)) out.add(src.trim());
        }
    }

    /**
     * 图片是否允许转存:内置默认 deny 常量 + 栏目 {@code imageDeny}(子串,逗号分隔)命中即拒;
     * {@code imageAllow} 非空时须命中其一。大小写不敏感。
     */
    static boolean imageAllowed(String url, SourceParseRules rules) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        for (String deny : DEFAULT_IMAGE_DENY) {
            if (!deny.isBlank() && u.contains(deny.toLowerCase(java.util.Locale.ROOT))) return false;
        }
        for (String deny : splitTokens(rules.imageDeny())) {
            if (u.contains(deny.toLowerCase(java.util.Locale.ROOT))) return false;
        }
        List<String> allow = splitTokens(rules.imageAllow());
        if (!allow.isEmpty()) {
            for (String a : allow) {
                if (u.contains(a.toLowerCase(java.util.Locale.ROOT))) return true;
            }
            return false;
        }
        return true;
    }

    private static List<String> splitTokens(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
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
