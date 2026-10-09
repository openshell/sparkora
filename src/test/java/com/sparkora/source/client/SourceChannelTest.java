package com.sparkora.source.client;

import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.source.service.SourceTableParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ==================== G2 容器优先正文(10-09) ====================

    @Test
    void G2_section_span型详情_容器优先正文非空() {
        // 乘联会型:div.read_content > div.text > section > span,全页仅 1 个 <p>(标题类),正文无 <p>
        String html = """
                <html><body>
                  <div class="nav">导航不应入正文</div>
                  <div class="read_content">
                    <div class="tit">2026年9月新能源乘用车厂商批发销量快讯</div>
                    <div class="text">
                      <section><span><span>9月新能源乘用车厂商批发销量预估达到167万辆，同比增长12%。</span></span></section>
                      <section><span>比亚迪继续领跑，销量超过40万辆，市场份额进一步提升。</span></section>
                    </div>
                  </div>
                  <div class="related">相关阅读不应入正文</div>
                </body></html>
                """;
        SourceChannelEntity ch = channel("{\"detail\":\"div.read_content\",\"tables\":\"\"}", "销量数据");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));

        assertTrue(c.text().contains("167万辆"), "容器内 section>span 正文应取到: " + c.text());
        assertTrue(c.text().contains("比亚迪"), "容器内第二段应取到");
        assertFalse(c.text().contains("导航不应入正文"), "容器选择器命中时不得回退 body 吞入导航");
        assertFalse(c.text().contains("相关阅读不应入正文"), "不得吞入相关阅读");
    }

    @Test
    void G2_多段落型详情_与现状等价() {
        String html = """
                <div class="article">
                  <p>第一段，长度足够，用于验证段落优先路径按段落拼接。</p>
                  <p>第二段，同样足够长，保证段落拼接总长超过阈值不会走整段兜底。</p>
                  <p>第三段，收尾。</p>
                </div>
                """;
        SourceChannelEntity ch = channel("{\"detail\":\".article\"}", "官方新闻");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));

        assertTrue(c.text().contains("第一段"), c.text());
        assertTrue(c.text().contains("第二段"), c.text());
        assertTrue(c.text().contains("第三段"), c.text());
        assertTrue(c.text().contains("\n\n"), "段落应以空行分隔");
    }

    @Test
    void G2_无容器选择器_保持旧行为零回归() {
        // 无 detail 选择器:容器=body,段落优先
        String html = "<html><body><div class=\"a\"><p>正文段落一</p></div></body></html>";
        SourceChannelEntity ch = channel(null, "行业资讯");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));
        assertTrue(c.text().contains("正文段落一"), c.text());
    }

    // ==================== G3 列表型结构化(10-09) ====================

    @Test
    void G3_listRows行结构_逐行单元格拼接含数值() {
        String html = """
                <div class="data">
                  <ul>
                    <li><span>1</span><span>比亚迪宋</span><span>52123</span></li>
                    <li><span>2</span><span>比亚迪秦</span><span>41008</span></li>
                  </ul>
                </div>
                """;
        SourceChannelEntity ch = channel("{\"detail\":\"div.data\",\"listRows\":\"div.data ul li\",\"rowCells\":\"span\"}", "销量数据");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));

        assertTrue(c.text().contains("1 | 比亚迪宋 | 52123"), "行文本应以 | 分隔: " + c.text());
        assertTrue(c.text().contains("2 | 比亚迪秦 | 41008"), "第二行数值不丢: " + c.text());
    }

    @Test
    void parseListRows_未配置选择器_零回归返回空() {
        org.jsoup.nodes.Element root = Jsoup.parse("<div class='data'><ul><li>1</li></ul></div>").selectFirst(".data");
        assertTrue(SourceTableParser.parseListRows(root, null, null).isEmpty());
        assertTrue(SourceTableParser.parseListRows(root, "", "span").isEmpty());
    }

    // ==================== G5 配图过滤(10-09) ====================

    @Test
    void G5_图标logo二维码被过滤_仅海报保留() {
        String html = """
                <div id="ArticleContent" class="contentDetailed">
                  <p>通用汽车第三季度在华销量超35.8万辆，新能源占比突破60%。</p>
                  <img src="https://imagecn.gasgoo.com/moblogo/News/UEditor/20261009/poster1.jpg">
                  <img src="https://imagecn.gasgoo.com/moblogo/news/qrcode/7047/4346.jpg">
                  <img src="https://c1.gasgoo.com/upload/companyLogo/0000/3521/0745.jpg">
                  <img src="https://c2.gasgoo.com/auto2019/images/common/APP@2x.png">
                  <img src="https://imagecn.gasgoo.com/moblogo/News/160_110/2026/10/0902432803.jpg">
                  <img src="https://imagecn.gasgoo.com/moblogo/News/UEditor/20261009/poster2.jpg">
                </div>
                """;
        SourceChannelEntity ch = channel("{\"detail\":\"#ArticleContent\",\"images\":\"#ArticleContent img\"}", "官方新闻");
        SourceContent c = client.detail(html, ch, new SourceItem("e1", "t", "u", null, List.of()));

        assertEquals(2, c.imageUrls().size(), "仅两张海报应保留: " + c.imageUrls());
        assertEquals("https://imagecn.gasgoo.com/moblogo/News/UEditor/20261009/poster1.jpg", c.imageUrls().get(0));
        assertEquals("https://imagecn.gasgoo.com/moblogo/News/UEditor/20261009/poster2.jpg", c.imageUrls().get(1));
    }

    @Test
    void G5_自定义imageDeny叠加默认规则() {
        SourceParseRules rules = SourceParseRules.parse("{\"imageDeny\":\"sponsorAD,thumb\"}");
        assertFalse(SiteSourceClient.imageAllowed("https://x.com/sponsorAD/a.jpg", rules), "配置 deny 命中");
        assertFalse(SiteSourceClient.imageAllowed("https://x.com/thumb/a.jpg", rules), "配置 deny 命中");
        assertFalse(SiteSourceClient.imageAllowed("https://x.com/common/a.jpg", rules), "内置默认 deny 命中");
        assertTrue(SiteSourceClient.imageAllowed("https://x.com/UEditor/poster.jpg", rules), "正常海报放行");
    }

    @Test
    void G5_imageAllow非空_须命中其一() {
        SourceParseRules rules = SourceParseRules.parse("{\"imageAllow\":\"moblogo/News/UEditor\"}");
        assertTrue(SiteSourceClient.imageAllowed("https://imagecn.gasgoo.com/moblogo/News/UEditor/a.jpg", rules));
        assertFalse(SiteSourceClient.imageAllowed("https://imagecn.gasgoo.com/other/b.jpg", rules));
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
