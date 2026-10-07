package com.sparkora.source.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSS/Atom 解析单测(10-05-source-crawl-base)。jsoup XML 解析,不发起 HTTP。
 */
class RssSourceClientTest {

    private final RssSourceClient client = new RssSourceClient();

    @Test
    void 解析RSS2_item取guid标题链接日期() {
        String xml = """
                <rss version="2.0"><channel>
                  <item>
                    <title>乘联会发布 10 月销量</title>
                    <link>https://www.cpcaauto.com/news/1</link>
                    <guid>cpca-news-1</guid>
                    <pubDate>Wed, 08 Oct 2026 09:00:00 GMT</pubDate>
                    <category>销量数据</category>
                  </item>
                  <item>
                    <title>第二条</title>
                    <link>https://www.cpcaauto.com/news/2</link>
                    <pubDate>Thu, 09 Oct 2026 09:00:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;
        List<SourceItem> items = client.list(xml, null);
        assertEquals(2, items.size());
        assertEquals("cpca-news-1", items.get(0).externalId());
        assertEquals("乘联会发布 10 月销量", items.get(0).title());
        assertEquals("https://www.cpcaauto.com/news/1", items.get(0).url());
        assertEquals("Wed, 08 Oct 2026 09:00:00 GMT", items.get(0).publishDate());
        assertEquals(List.of("销量数据"), items.get(0).tags());
        // 第二条无 guid → externalId 回退链接
        assertEquals("https://www.cpcaauto.com/news/2", items.get(1).externalId());
    }

    @Test
    void RSS自带description详情阶段作为正文() {
        String xml = """
                <rss version="2.0"><channel>
                  <item>
                    <title>标题</title>
                    <link>https://x.com/1</link>
                    <guid>g1</guid>
                    <description><![CDATA[<p>正文第一段</p><p>正文第二段</p>]]></description>
                  </item>
                </channel></rss>
                """;
        List<SourceItem> items = client.list(xml, null);
        assertEquals(1, items.size());
        SourceContent c = client.detail(null, null, items.get(0));
        assertTrue(c.text().contains("正文第一段") && c.text().contains("正文第二段"), "RSS 自带正文应解析: " + c.text());
    }

    @Test
    void 解析Atom_entry取id标题链接_href形式() {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>Atom 条目</title>
                    <link href="https://example.com/a/1"/>
                    <id>atom-1</id>
                    <published>2026-10-08T09:00:00Z</published>
                    <category term="行业资讯"/>
                  </entry>
                </feed>
                """;
        List<SourceItem> items = client.list(xml, null);
        assertEquals(1, items.size());
        assertEquals("atom-1", items.get(0).externalId());
        assertEquals("https://example.com/a/1", items.get(0).url());
        assertEquals("2026-10-08T09:00:00Z", items.get(0).publishDate());
        assertEquals(List.of("行业资讯"), items.get(0).tags());
    }

    @Test
    void pubDate缺失_容错为null且不丢条目() {
        String xml = """
                <rss version="2.0"><channel>
                  <item><title>无日期</title><link>https://x.com/1</link></item>
                </channel></rss>
                """;
        List<SourceItem> items = client.list(xml, null);
        assertEquals(1, items.size());
        assertNull(items.get(0).publishDate());
    }

    @Test
    void 空或非法HTML_降级空列表不抛() {
        assertTrue(client.list(null, null).isEmpty());
        assertTrue(client.list("<html><body>非 feed</body></html>", null).isEmpty());
    }
}
