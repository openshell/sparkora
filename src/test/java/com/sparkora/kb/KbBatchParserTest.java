package com.sparkora.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KbBatchParser} 纯静态解析器单测(10-03 C 批量导入)。
 * 覆盖 CSV(引号/内嵌逗号换行/{@code ""} 转义/缺列)、JSON(数组/字符串 tags/非数组/大小写字段)、
 * Markdown(H1 分段/无 H1)、format 探测与日期解析。
 */
class KbBatchParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== CSV ====================

    @Test
    void csv_标准表头与数据行() {
        String raw = "title,domain,content,source,tags,effectiveFrom,effectiveTo\n"
                + "充电桩选择,充电,看功率与物业,官网,充电;安装,2026-01-01,2026-12-31\n";
        List<KbImportRow> rows = KbBatchParser.parseCsv(raw);
        assertEquals(1, rows.size());
        KbImportRow r = rows.get(0);
        assertEquals("充电桩选择", r.getTitle());
        assertEquals("充电", r.getDomain());
        assertEquals("看功率与物业", r.getContent());
        assertEquals("官网", r.getSource());
        assertEquals(List.of("充电", "安装"), r.getTags());
        assertEquals(LocalDate.of(2026, 1, 1), r.getEffectiveFrom());
        assertEquals(LocalDate.of(2026, 12, 31), r.getEffectiveTo());
        assertNull(r.getParseError());
    }

    @Test
    void csv_表头大小写与空白不敏感() {
        String raw = " Title , DOMAIN , CONTENT \nT,通用,正文\n";
        List<KbImportRow> rows = KbBatchParser.parseCsv(raw);
        assertEquals("T", rows.get(0).getTitle());
        assertEquals("通用", rows.get(0).getDomain());
    }

    @Test
    void csv_引号包裹内嵌逗号() {
        String raw = "title,content\n\"标题,带逗号\",\"正文, 也带逗号\"\n";
        List<KbImportRow> rows = KbBatchParser.parseCsv(raw);
        assertEquals("标题,带逗号", rows.get(0).getTitle());
        assertEquals("正文, 也带逗号", rows.get(0).getContent());
    }

    @Test
    void csv_引号包裹内嵌换行与双引号转义() {
        String raw = "title,content\n\"多行\",\"第一行\n第二行 \"\"引用\"\"\"\n";
        List<KbImportRow> rows = KbBatchParser.parseCsv(raw);
        assertEquals(1, rows.size());
        assertEquals("多行", rows.get(0).getTitle());
        assertEquals("第一行\n第二行 \"引用\"", rows.get(0).getContent());
    }

    @Test
    void csv_末行无换行也要产出() {
        List<KbImportRow> rows = KbBatchParser.parseCsv("title,content\nT,正文");
        assertEquals(1, rows.size());
        assertEquals("正文", rows.get(0).getContent());
    }

    @Test
    void csv_缺title列_整体失败() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> KbBatchParser.parseCsv("domain,content\n通用,正文\n"));
        assertTrue(ex.getMessage().contains("title"), ex.getMessage());
    }

    @Test
    void csv_缺content列_整体失败() {
        assertThrows(IllegalArgumentException.class,
                () -> KbBatchParser.parseCsv("title,domain\nT,通用\n"));
    }

    @Test
    void csv_空内容_整体失败() {
        assertThrows(IllegalArgumentException.class, () -> KbBatchParser.parseCsv(""));
    }

    @Test
    void csv_非法日期_行级parseError不整批中断() {
        String raw = "title,content,effectiveFrom\nT1,正文一,not-a-date\nT2,正文二,2026-01-01\n";
        List<KbImportRow> rows = KbBatchParser.parseCsv(raw);
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0).getParseError());
        assertTrue(rows.get(0).getParseError().contains("yyyy-MM-dd"));
        assertNull(rows.get(1).getParseError());
        assertEquals(LocalDate.of(2026, 1, 1), rows.get(1).getEffectiveFrom());
    }

    // ==================== JSON ====================

    @Test
    void json_对象数组_数组tags与字符串tags兼容() {
        String raw = "[{\"title\":\"A\",\"domain\":\"通用\",\"content\":\"正文\",\"tags\":[\"x\",\"y\"]},"
                + "{\"title\":\"B\",\"content\":\"正文2\",\"tags\":\"m;n\"}]";
        List<KbImportRow> rows = KbBatchParser.parseJson(raw, mapper);
        assertEquals(2, rows.size());
        assertEquals(List.of("x", "y"), rows.get(0).getTags());
        assertEquals(List.of("m", "n"), rows.get(1).getTags());
    }

    @Test
    void json_驼峰生效期字段被识别() {
        String raw = "[{\"title\":\"A\",\"content\":\"正文\",\"effectiveFrom\":\"2026-03-01\",\"effectiveTo\":\"2026-04-01\"}]";
        KbImportRow r = KbBatchParser.parseJson(raw, mapper).get(0);
        assertEquals(LocalDate.of(2026, 3, 1), r.getEffectiveFrom());
        assertEquals(LocalDate.of(2026, 4, 1), r.getEffectiveTo());
    }

    @Test
    void json_非数组根节点_整体失败() {
        assertThrows(IllegalArgumentException.class,
                () -> KbBatchParser.parseJson("{\"title\":\"A\"}", mapper));
    }

    @Test
    void json_数组元素非对象_整体失败() {
        assertThrows(IllegalArgumentException.class,
                () -> KbBatchParser.parseJson("[1,2]", mapper));
    }

    @Test
    void json_非法JSON_整体失败() {
        assertThrows(IllegalArgumentException.class,
                () -> KbBatchParser.parseJson("{not json", mapper));
    }

    // ==================== Markdown ====================

    @Test
    void markdown_按H1分段() {
        String raw = "# 第一篇\n正文一\n\n# 第二篇\n正文二\n多行\n";
        List<KbImportRow> rows = KbBatchParser.parseMarkdown(raw, "fallback");
        assertEquals(2, rows.size());
        assertEquals("第一篇", rows.get(0).getTitle());
        assertEquals("正文一", rows.get(0).getContent());
        assertEquals("第二篇", rows.get(1).getTitle());
        assertEquals("正文二\n多行", rows.get(1).getContent());
    }

    @Test
    void markdown_H2不被当分段() {
        String raw = "# 主标题\n## 二级\ntext\n";
        List<KbImportRow> rows = KbBatchParser.parseMarkdown(raw, "fallback");
        assertEquals(1, rows.size());
        assertEquals("主标题", rows.get(0).getTitle());
        assertTrue(rows.get(0).getContent().contains("## 二级"));
    }

    @Test
    void markdown_无H1_整文件一篇用fallback标题() {
        String raw = "纯正文\n第二行";
        List<KbImportRow> rows = KbBatchParser.parseMarkdown(raw, "文件名");
        assertEquals(1, rows.size());
        assertEquals("文件名", rows.get(0).getTitle());
        assertEquals("纯正文\n第二行", rows.get(0).getContent());
    }

    @Test
    void markdown_空文件_产生一行供服务层判空正文() {
        List<KbImportRow> rows = KbBatchParser.parseMarkdown("", "文件名");
        assertEquals(1, rows.size());
        assertEquals("文件名", rows.get(0).getTitle());
        assertEquals("", rows.get(0).getContent());
    }

    // ==================== detectFormat ====================

    @Test
    void detectFormat_后缀优先() {
        assertEquals("csv", KbBatchParser.detectFormat("a.csv", "# 看起来像md"));
        assertEquals("json", KbBatchParser.detectFormat("a.json", "title,content"));
        assertEquals("markdown", KbBatchParser.detectFormat("a.md", "title,content"));
        assertEquals("markdown", KbBatchParser.detectFormat("a.markdown", ""));
    }

    @Test
    void detectFormat_无后缀按内容嗅探() {
        assertEquals("json", KbBatchParser.detectFormat("data", "[{\"title\":\"A\"}]"));
        assertEquals("json", KbBatchParser.detectFormat(null, "{}"));
        assertEquals("markdown", KbBatchParser.detectFormat("data", "# 标题\n正文"));
        assertEquals("csv", KbBatchParser.detectFormat("data", "title,content\nT,正文"));
        assertEquals("markdown", KbBatchParser.detectFormat("data", "纯文本无逗号"));
    }

    @Test
    void normalizeFormat_md兼容_非法拒绝() {
        assertEquals("csv", KbBatchParser.normalizeFormat(" CSv "));
        assertEquals("markdown", KbBatchParser.normalizeFormat("md"));
        assertEquals("markdown", KbBatchParser.normalizeFormat("MARKDOWN"));
        assertNull(KbBatchParser.normalizeFormat(null));
        assertNull(KbBatchParser.normalizeFormat("  "));
        assertThrows(IllegalArgumentException.class, () -> KbBatchParser.normalizeFormat("xml"));
    }

    // ==================== 公共工具 ====================

    @Test
    void parseDate_ISO与非法() {
        assertEquals(LocalDate.of(2026, 5, 1), KbBatchParser.parseDate("2026-05-01"));
        assertNull(KbBatchParser.parseDate("  "));
        assertNull(KbBatchParser.parseDate(null));
        assertThrows(IllegalArgumentException.class, () -> KbBatchParser.parseDate("2026/05/01"));
    }

    @Test
    void splitTags_分号拆分trim去空() {
        assertEquals(List.of("a", "b"), KbBatchParser.splitTags(" a ; b ; "));
        assertEquals(List.of(), KbBatchParser.splitTags(null));
        assertEquals(List.of(), KbBatchParser.splitTags("  "));
        assertEquals(List.of("单"), KbBatchParser.splitTags("单"));
    }

    @Test
    void parseCsv_空白数据行跳过() {
        String raw = "title,content\nT,正文\n\n  \n";
        assertEquals(1, KbBatchParser.parseCsv(raw).size());
    }
}
