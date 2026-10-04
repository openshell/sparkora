package com.sparkora.web.controller;

import com.sparkora.kb.service.KbBatchImportService;
import com.sparkora.kb.service.KbDocService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KbDocController HTTP 契约测试(10-03 C 批量导入):
 * /docs/batch 200 结构、整体解析失败 400、缺 file 400;以及端点角色守卫声明(写=ADMIN/EDITOR)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbDocControllerContractTest {

    @Mock KbDocService service;
    @Mock KbBatchImportService batchImportService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KbDocController(service, batchImportService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void batch_成功_返回totalSuccessFailedResults结构() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", 0);
        row.put("title", "A");
        row.put("success", true);
        row.put("error", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", 1);
        out.put("success", 1);
        out.put("failed", 0);
        out.put("results", List.of(row));
        when(batchImportService.importBatch(eq("kb.csv"), eq(null), any(), eq("system"))).thenReturn(out);

        MockMultipartFile file = new MockMultipartFile("file", "kb.csv", "text/csv",
                "title,content\nA,正文\n".getBytes());
        mvc.perform(multipart("/api/kb/docs/batch").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.success").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.results[0].index").value(0))
                .andExpect(jsonPath("$.data.results[0].title").value("A"))
                .andExpect(jsonPath("$.data.results[0].success").value(true));
    }

    @Test
    void batch_显式format透传() throws Exception {
        when(batchImportService.importBatch(eq("data.txt"), eq("csv"), any(), any()))
                .thenReturn(Map.of("total", 0, "success", 0, "failed", 0, "results", List.of()));

        MockMultipartFile file = new MockMultipartFile("file", "data.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/kb/docs/batch").file(file).param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(batchImportService).importBatch(eq("data.txt"), eq("csv"), any(), any());
    }

    @Test
    void batch_整体解析失败_400中文提示() throws Exception {
        when(batchImportService.importBatch(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("CSV 表头必须包含 title 与 content 列"));

        MockMultipartFile file = new MockMultipartFile("file", "kb.csv", "text/csv",
                "domain,content\n通用,正文\n".getBytes());
        mvc.perform(multipart("/api/kb/docs/batch").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("CSV 表头必须包含 title 与 content 列"));
    }

    @Test
    void batch_缺file参数_400() throws Exception {
        mvc.perform(multipart("/api/kb/docs/batch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        verify(batchImportService, never()).importBatch(any(), any(), any(), any());
    }

    @Test
    void batch_空文件_400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        mvc.perform(multipart("/api/kb/docs/batch").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请上传导入文件"));
    }

    @Test
    void batch_下游异常_500() throws Exception {
        when(batchImportService.importBatch(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        MockMultipartFile file = new MockMultipartFile("file", "kb.csv", "text/csv", "x".getBytes());
        mvc.perform(multipart("/api/kb/docs/batch").file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    /** 写端点角色守卫:@PreAuthorize 只允许 ADMIN/EDITOR(viewer 403 由方法安全拦截)。 */
    @Test
    void batch_角色守卫声明为ADMIN_EDITOR() throws Exception {
        Method m = KbDocController.class.getMethod("batch",
                org.springframework.web.multipart.MultipartFile.class, String.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa, "batch 端点必须带 @PreAuthorize");
        assertEquals("hasAnyRole('ADMIN','EDITOR')", pa.value());
        assertTrue(pa.value().contains("ADMIN") && pa.value().contains("EDITOR"));
    }
}
