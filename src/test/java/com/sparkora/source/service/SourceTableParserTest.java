package com.sparkora.source.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 表格 → 保留行列文本单测(10-05-source-crawl-base,AC-B7)。
 * 断言列数/数值不丢失(避免下游 TextChunker「段内换行转空格」压平)。
 */
class SourceTableParserTest {

    @Test
    void 表格转行文本_列数与数值齐全() {
        String html = """
                <div class="article">
                  <table>
                    <tr><th>车型</th><th>销量</th><th>同比</th></tr>
                    <tr><td>比亚迪宋</td><td>52123</td><td>+12.3%</td></tr>
                    <tr><td>比亚迪秦</td><td>41008</td><td>-3.1%</td></tr>
                  </table>
                </div>
                """;
        Element root = Jsoup.parse(html).selectFirst(".article");
        List<String> tables = SourceTableParser.parseTables(root, "table");
        assertEquals(1, tables.size(), "应识别出 1 张表");
        String text = tables.get(0);
        String[] lines = text.split("\n");
        assertEquals(3, lines.length, "表头 + 2 数据行");
        assertTrue(lines[0].contains("车型") && lines[0].contains("销量") && lines[0].contains("同比"),
                "表头列齐: " + lines[0]);
        assertTrue(lines[1].contains("比亚迪宋") && lines[1].contains("52123") && lines[1].contains("+12.3%"),
                "首行数值不丢: " + lines[1]);
        assertTrue(lines[2].contains("比亚迪秦") && lines[2].contains("41008") && lines[2].contains("-3.1%"),
                "次行数值不丢: " + lines[2]);
    }

    @Test
    void 空单元格保留占位_列不错位() {
        Element table = Jsoup.parse("<table><tr><td>A</td><td></td><td>C</td></tr></table>").selectFirst("table");
        String text = SourceTableParser.tableToText(table);
        assertEquals("A |  | C", text);
    }

    @Test
    void 选择器不匹配_返回空() {
        Element root = Jsoup.parse("<div><p>无表格</p></div>").body();
        assertTrue(SourceTableParser.parseTables(root, "table.no-such").isEmpty());
    }

    @Test
    void null根_返回空列表不抛() {
        assertTrue(SourceTableParser.parseTables(null, "table").isEmpty());
    }
}
