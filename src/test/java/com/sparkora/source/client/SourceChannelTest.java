package com.sparkora.source.client;

import com.sparkora.domain.entity.SourceChannelEntity;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一源多栏目解析单测(10-05-source-crawl-base,AC-B11):
 * 同一源两个栏目各用自身 parse_rules/category 解析、互不影响;单栏目等价「一源一列表页」。
 */
class SourceChannelTest {

    private final SiteSourceClient client = new SiteSourceClient();

    private static SourceChannelEntity channel(String parseRules, String category) {
        SourceChannelEntity c = new SourceChannelEntity();
        c.setId(1L);
        c.setParseRules(parseRules);
        c.setCategory(category);
        c.setDetailBaseUrl("https://www.cpcaauto.com");
        return c;
    }

    @Test
    void 两栏目各用自身parseRules_解析互不影响() {
        String html = """
                <html><body>
                  <div class="news-list">
                    <li><a href="/news/1">行业新闻一</a></li>
                  </div>
                  <div class="sales-list">
                    <li><a href="/sales/1">车市解读一</a></li>
                  </div>
                </body></html>
                """;
        SourceChannelEntity news = channel("{\"list\":\".news-list li\",\"link\":\"a\",\"title\":\"a\"}", "行业资讯");
        SourceChannelEntity sales = channel("{\"list\":\".sales-list li\",\"link\":\"a\",\"title\":\"a\"}", "销量数据");

        List<SourceItem> newsItems = client.list(html, news);
        List<SourceItem> salesItems = client.list(html, sales);

        assertEquals(1, newsItems.size());
        assertEquals("行业新闻一", newsItems.get(0).title());
        assertEquals(1, salesItems.size());
        assertEquals("车市解读一", salesItems.get(0).title());
    }

    @Test
    void 单栏目源_等价一源一列表页() {
        String html = "<ul class=\"list\"><li><a href=\"/a/1\">唯一列表项</a></li></ul>";
        SourceChannelEntity only = channel("{\"list\":\".list li\",\"link\":\"a\",\"title\":\"a\"}", "官方新闻");

        List<SourceItem> items = client.list(html, only);
        assertEquals(1, items.size());
        assertEquals("唯一列表项", items.get(0).title());
    }

    @Test
    void bydOnly规则_只保留BYD条目() {
        String html = """
                <ul class="list">
                  <li><a href="/a/1">比亚迪海豹申报</a></li>
                  <li><a href="/a/2">某其他车企申报</a></li>
                  <li><a href="/a/3">BYD 宋 PLUS 申报</a></li>
                </ul>
                """;
        SourceChannelEntity ch = channel("{\"list\":\".list li\",\"link\":\"a\",\"title\":\"a\",\"bydOnly\":true}", "政策公示");

        List<SourceItem> items = client.list(html, ch);
        assertEquals(2, items.size(), "其他车企被过滤");
        assertTrue(items.stream().allMatch(i -> i.title().contains("比亚迪") || i.title().contains("BYD")));
    }

    @Test
    void 详情_表格并入正文保留行列() {
        String html = """
                <div class="article">
                  <p>正文段落</p>
                  <table><tr><td>宋</td><td>52123</td></tr></table>
                </div>
                """;
        SourceChannelEntity ch = channel("{\"detail\":\".article\",\"tables\":\"table\"}", "销量数据");
        SourceItem item = new SourceItem("e1", "t", "https://x/d/1", null, List.of());

        SourceContent c = client.detail(html, ch, item);

        assertTrue(c.text().contains("正文段落"), "段落应在正文");
        assertTrue(c.text().contains("宋 | 52123"), "表格行文本应在正文(数值不丢): " + c.text());
    }

    @Test
    void 详情_图片抽取保留原始链() {
        String html = "<div class=\"article\"><img src=\"/uploads/a.jpg\"><img data-src=\"https://cdn.x.com/b.png\"></div>";
        SourceChannelEntity ch = channel("{\"detail\":\".article\",\"images\":\"img\"}", "行业资讯");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));
        assertEquals(List.of("/uploads/a.jpg", "https://cdn.x.com/b.png"), c.imageUrls());
    }

    @Test
    void 空parseRules_列表降级空不抛() {
        SourceChannelEntity ch = channel(null, "行业资讯");
        assertTrue(client.list("<html></html>", ch).isEmpty());
    }

    @Test
    void parseRules非法JSON_降级空规则不抛() {
        SourceParseRules rules = SourceParseRules.parse("{ 非法 json");
        assertEquals(SourceParseRules.EMPTY.listSelector(), rules.listSelector());
    }
}
