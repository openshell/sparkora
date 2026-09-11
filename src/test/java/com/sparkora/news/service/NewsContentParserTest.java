package com.sparkora.news.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新闻详情页正文抽取纯函数单测(jsoup 解析,不发起 HTTP)。
 */
class NewsContentParserTest {

    @Test
    void 抽取标题正文日期_br转换行_图片并入正文() {
        String html = """
                <html><body>
                  <h1 class="cmp-news__detail-title">比亚迪新闻标题</h1>
                  <div class="cmp-news__detail-date">发布于 2026-09-01 17:08:31</div>
                  <div class="cmp-news__detail-content">
                    <div class="news-text"><p>第一行<br>第二行</p></div>
                    <div class="news-text"><p>第二段</p></div>
                    <div class="news-image"><img src="https://pic.example.com/a.jpg"></div>
                  </div>
                </body></html>
                """;
        NewsContentParser.Parsed p = NewsContentParser.parse(html);
        assertEquals("比亚迪新闻标题", p.title());
        assertEquals("2026-09-01 17:08:31", p.publishDate());
        assertTrue(p.content().contains("第一行\n第二行"), () -> "br 应转换为换行: " + p.content());
        assertTrue(p.content().contains("第二段"));
        assertTrue(p.content().contains("[图片] https://pic.example.com/a.jpg"));
    }

    @Test
    void 图片型新闻_正文仅图片_标题日期仍可抽() {
        String html = """
                <div class="cmp-news__detail-content">
                  <div class="news-image"><img src="/img/x.png"></div>
                </div>
                """;
        NewsContentParser.Parsed p = NewsContentParser.parse(html);
        assertTrue(p.content().contains("[图片] /img/x.png"));
    }

    @Test
    void 空HTML或结构缺失_降级不抛() {
        NewsContentParser.Parsed p = NewsContentParser.parse(null);
        assertEquals("", p.content());
        assertNull(p.title());
        NewsContentParser.Parsed p2 = NewsContentParser.parse("<html><body>无匹配选择器</body></html>");
        assertEquals("", p2.content());
        assertNull(p2.title());
    }

    @Test
    void 日期提取_支持秒缺失与纯日期() {
        assertEquals("2026-09-01 17:08:31", NewsContentParser.extractDate("发布于 2026-09-01 17:08:31"));
        assertEquals("2026-09-01 17:08:00", NewsContentParser.extractDate("2026-09-01 17:08"));
        assertEquals("2026-09-01 00:00:00", NewsContentParser.extractDate("2026-09-01"));
        assertNull(NewsContentParser.extractDate("无日期"));
    }
}
