package com.sparkora.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.mapper.KbDocMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KbBatchImportService} 单测(10-03 C 批量导入)。
 * mock {@link KbDocService} + {@link KbDocMapper},覆盖逐条成功、单条失败不阻断、
 * 库内/批内重复跳过、超上限报错、整体解析失败抛出、results 结构。
 */
class KbBatchImportServiceTest {

    private final KbDocService docService = mock(KbDocService.class);
    private final KbDocMapper docMapper = mock(KbDocMapper.class);
    private final KbBatchImportService service =
            new KbBatchImportService(docService, docMapper, new ObjectMapper());

    private KbDocEntity existing(String title, String domain) {
        KbDocEntity d = new KbDocEntity();
        d.setTitle(title);
        d.setDomain(domain);
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> importCsv(String raw) {
        when(docMapper.selectList(any())).thenReturn(List.of());
        return service.importBatch("import.csv", null, raw, "alice");
    }

    // ==================== 逐条成功 ====================

    @Test
    void 逐条成功_计数与results结构齐全() {
        String raw = "title,domain,content,source,tags,effectiveFrom\n"
                + "A,充电,正文A,来源,标签1;标签2,2026-01-01\n"
                + "B,通用,正文B,,, \n";
        Map<String, Object> out = importCsv(raw);

        assertEquals(2, out.get("total"));
        assertEquals(2, out.get("success"));
        assertEquals(0, out.get("failed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals(2, results.size());
        Map<String, Object> first = results.get(0);
        assertEquals(0, first.get("index"));
        assertEquals("A", first.get("title"));
        assertEquals(Boolean.TRUE, first.get("success"));
        assertNull(first.get("error"));

        verify(docService).create(eq("A"), eq("充电"), eq("来源"), eq(List.of("标签1", "标签2")),
                eq(LocalDate.of(2026, 1, 1)), any(), eq("正文A"), eq("alice"));
        verify(docService).create(eq("B"), eq("通用"), any(), any(), any(), any(), eq("正文B"), eq("alice"));
    }

    // ==================== 单条失败不阻断 ====================

    @Test
    void 单条失败不阻断其余_非法domain() {
        String raw = "title,domain,content\nA,充电,正文A\nB,财经,正文B\nC,保养,正文C\n";
        Map<String, Object> out = importCsv(raw);

        assertEquals(3, out.get("total"));
        assertEquals(2, out.get("success"));
        assertEquals(1, out.get("failed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertTrue((Boolean) results.get(1).get("success") == false);
        assertTrue(((String) results.get(1).get("error")).contains("受控词表"));
        // 第三条(合法)仍被处理
        verify(docService).create(eq("C"), eq("保养"), any(), any(), any(), any(), eq("正文C"), eq("alice"));
    }

    @Test
    void 单条create抛异常_记为失败不阻断() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("嵌入服务不可用")).when(docService)
                .create(eq("A"), any(), any(), any(), any(), any(), any(), anyString());

        String raw = "title,domain,content\nA,通用,正文A\nB,通用,正文B\n";
        Map<String, Object> out = service.importBatch("x.csv", null, raw, "bob");

        assertEquals(1, out.get("success"));
        assertEquals(1, out.get("failed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertTrue(((String) results.get(0).get("error")).contains("嵌入服务不可用"));
        assertTrue((Boolean) results.get(1).get("success"));
    }

    @Test
    void 空标题与空正文_行级失败且不写库() {
        String raw = "title,domain,content\n,通用,正文\nT,通用,\n";
        Map<String, Object> out = importCsv(raw);
        assertEquals(2, out.get("failed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals("标题不能为空", results.get(0).get("error"));
        assertEquals("正文不能为空", results.get(1).get("error"));
        verify(docService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 正文超长_行级失败() {
        String longContent = "字".repeat(KbBatchImportService.MAX_CONTENT_LEN + 1);
        String raw = "title,content\nT," + longContent + "\n";
        Map<String, Object> out = importCsv(raw);
        assertEquals(1, out.get("failed"));
        assertTrue(((String) ((List<Map<String, Object>>) out.get("results")).get(0).get("error")).contains("50000"));
    }

    @Test
    void 来源超长_行级失败且不写库() {
        String longSource = "x".repeat(KbBatchImportService.MAX_SOURCE_LEN + 1);
        String raw = "title,content,source\nT,正文," + longSource + "\n";
        Map<String, Object> out = importCsv(raw);
        assertEquals(1, out.get("failed"));
        assertTrue(((String) ((List<Map<String, Object>>) out.get("results")).get(0).get("error")).contains("200"));
        verify(docService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== 去重 ====================

    @Test
    void 库内重复_跳过并回报中文() {
        when(docMapper.selectList(any())).thenReturn(List.of(existing("A", "通用")));
        String raw = "title,domain,content\nA,通用,正文\n";
        Map<String, Object> out = service.importBatch("x.csv", null, raw, "alice");

        assertEquals(1, out.get("failed"));
        assertEquals(0, out.get("success"));
        assertEquals("已存在同标题同领域文档，已跳过",
                ((List<Map<String, Object>>) out.get("results")).get(0).get("error"));
        verify(docService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 批内重复_仅首条处理后续跳过() {
        String raw = "title,domain,content\nA,通用,正文1\nA,通用,正文2\n";
        Map<String, Object> out = importCsv(raw);

        assertEquals(2, out.get("total"));
        assertEquals(1, out.get("success"));
        assertEquals(1, out.get("failed"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertTrue((Boolean) results.get(0).get("success"));
        assertEquals("已存在同标题同领域文档，已跳过", results.get(1).get("error"));
        verify(docService, times(1)).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 同标题不同领域_不视为重复() {
        String raw = "title,domain,content\nA,通用,正文1\nA,充电,正文2\n";
        Map<String, Object> out = importCsv(raw);
        assertEquals(2, out.get("success"));
        assertEquals(0, out.get("failed"));
    }

    // ==================== 整体失败 / 上限 ====================

    @Test
    void 整体解析失败_CSV缺表头_抛出() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.importBatch("x.csv", null, "domain,content\n通用,正文\n", "alice"));
        verify(docService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 整体解析失败_JSON非数组_抛出() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importBatch("x.json", null, "{\"title\":\"A\"}", "alice"));
    }

    @Test
    void 不支持格式_抛出() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importBatch("x.xml", "xml", "<a/>", "alice"));
    }

    @Test
    void 超过单批上限200_抛出且不写库() {
        StringBuilder sb = new StringBuilder("title,content\n");
        for (int i = 0; i <= KbBatchImportService.MAX_ROWS; i++) sb.append("T").append(i).append(",正文\n");
        when(docMapper.selectList(any())).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importBatch("x.csv", null, sb.toString(), "alice"));
        assertTrue(ex.getMessage().contains("200"));
        verify(docService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== 格式判定与 Markdown ====================

    @Test
    void markdown_无H1_标题取文件名去后缀() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> out = service.importBatch("/tmp/充电常识.md", null, "纯正文", "alice");

        assertEquals(1, out.get("success"));
        verify(docService).create(eq("充电常识"), any(), any(), any(), any(), any(), eq("纯正文"), eq("alice"));
    }

    @Test
    void 显式format覆盖后缀() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        // 文件名 .txt 但显式指定 csv
        Map<String, Object> out = service.importBatch("data.txt", "csv", "title,content\nT,正文\n", "alice");
        assertEquals(1, out.get("success"));
    }

    @Test
    void operator为空_归system() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        service.importBatch("x.csv", null, "title,content\nT,正文\n", null);
        verify(docService).create(any(), any(), any(), any(), any(), any(), any(), eq("system"));
    }
}
