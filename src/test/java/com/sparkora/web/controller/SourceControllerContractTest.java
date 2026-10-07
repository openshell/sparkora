package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.SourceContentDTO;
import com.sparkora.domain.dto.SourceUpdateDTO;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.domain.entity.SourceJobEntity;
import com.sparkora.source.service.SourceContentService;
import com.sparkora.source.service.SourceJobService;
import com.sparkora.source.service.SourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SourceController HTTP 契约测试(10-05-source-crawl-base,AC-B9):
 * 内容查询分页/筛选、详情含正文/切块数、source=byd 字段齐备;以及端点角色守卫声明(读=三角色,写=ADMIN/EDITOR)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceControllerContractTest {

    @Mock SourceService sourceService;
    @Mock SourceJobService jobService;
    @Mock SourceContentService contentService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SourceController(sourceService, jobService, contentService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void 内容列表_分页筛选_返回PageResult() throws Exception {
        SourceContentDTO row = new SourceContentDTO();
        row.setId(1L);
        row.setSourceId(5L);
        row.setTitle("工信部第411批公示");
        row.setCategory("政策公示");
        row.setSource("source");
        row.setChunkCount(3L);
        when(contentService.list(eq(1L), eq(12L), eq("工信部"), eq("政策公示"), eq(5L)))
                .thenReturn(new PageResult<>(List.of(row), 1, 1, 12));

        mvc.perform(get("/api/source-contents")
                        .param("page", "1").param("size", "12")
                        .param("keyword", "工信部").param("category", "政策公示").param("sourceId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(1))
                .andExpect(jsonPath("$.data.rows[0].title").value("工信部第411批公示"))
                .andExpect(jsonPath("$.data.rows[0].category").value("政策公示"))
                .andExpect(jsonPath("$.data.rows[0].chunkCount").value(3));
    }

    @Test
    void 内容详情_含正文_不存在404() throws Exception {
        SourceContentDTO d = new SourceContentDTO();
        d.setId(9L);
        d.setTitle("销量表");
        d.setContent("车型 | 销量\n宋 | 52123");
        d.setChunkCount(2L);
        when(contentService.get(9L)).thenReturn(d);
        when(contentService.get(999L)).thenThrow(new IllegalArgumentException("内容不存在"));

        mvc.perform(get("/api/source-contents/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value("车型 | 销量\n宋 | 52123"))
                .andExpect(jsonPath("$.data.chunkCount").value(2));

        mvc.perform(get("/api/source-contents/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void source为byd_条目返回U条件渲染字段() throws Exception {
        SourceContentDTO d = new SourceContentDTO();
        d.setId(2L);
        d.setSource("byd-news");
        d.setTitle("比亚迪官方新闻");
        d.setUrl("https://www.byd.com/cn/detail1");
        d.setPublishDate(LocalDateTime.of(2026, 10, 8, 9, 0));
        d.setChunkCount(4L);
        when(contentService.get(2L)).thenReturn(d);

        mvc.perform(get("/api/source-contents/2"))
                .andExpect(jsonPath("$.data.source").value("byd-news"))
                .andExpect(jsonPath("$.data.chunkCount").value(4))
                .andExpect(jsonPath("$.data.url").value("https://www.byd.com/cn/detail1"));
    }

    @Test
    void 信源详情_返回channels数组() throws Exception {
        SourceEntity s = new SourceEntity();
        s.setId(5L);
        s.setName("乘联会");
        SourceChannelEntity c1 = new SourceChannelEntity();
        c1.setId(10L);
        c1.setName("行业新闻");
        c1.setCategory("行业资讯");
        SourceChannelEntity c2 = new SourceChannelEntity();
        c2.setId(11L);
        c2.setName("车市解读");
        c2.setCategory("销量数据");
        s.setChannels(List.of(c1, c2));
        when(sourceService.get(5L)).thenReturn(s);

        mvc.perform(get("/api/sources/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                .andExpect(jsonPath("$.data.channels[0].name").value("行业新闻"))
                .andExpect(jsonPath("$.data.channels[1].category").value("销量数据"));
    }

    @Test
    void 手动采集_指定channel_返回jobId() throws Exception {
        when(jobService.createJob(eq(5L), eq(10L), eq("MANUAL"), isNull())).thenReturn(77L);

        mvc.perform(post("/api/sources/5/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(77));
    }

    @Test
    void 编辑源_200回详情() throws Exception {
        SourceEntity s = new SourceEntity();
        s.setId(5L);
        s.setEnabled(false);
        when(sourceService.update(eq(5L), any(SourceUpdateDTO.class))).thenReturn(s);

        mvc.perform(put("/api/sources/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void 重试任务_无失败项400() throws Exception {
        when(jobService.retry(9L)).thenThrow(new IllegalArgumentException("该任务没有可重试的失败项"));

        mvc.perform(post("/api/source-jobs/9/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void 任务不存在_404() throws Exception {
        when(jobService.get(9L)).thenReturn(null);
        mvc.perform(get("/api/source-jobs/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== 角色守卫声明 ====================

    private static PreAuthorize pa(String name, Class<?>... params) throws Exception {
        Method m = SourceController.class.getMethod(name, params);
        return m.getAnnotation(PreAuthorize.class);
    }

    @Test
    void 读端点三角色_写端点ADMIN_EDITOR() throws Exception {
        assertRoles(pa("list"), true);
        assertRoles(pa("get", Long.class), true);
        assertRoles(pa("listJobs", Long.class), true);
        assertRoles(pa("getJob", Long.class), true);
        assertRoles(pa("listContents", long.class, long.class, String.class, String.class, Long.class), true);
        assertRoles(pa("getContent", Long.class), true);

        assertRoles(pa("update", Long.class, SourceUpdateDTO.class), false);
        assertRoles(pa("collect", Long.class, java.util.Map.class), false);
        assertRoles(pa("retryJob", Long.class), false);
    }

    private static void assertRoles(PreAuthorize p, boolean viewerAllowed) {
        assertNotNull(p, "端点必须带 @PreAuthorize");
        assertTrue(p.value().contains("ADMIN") && p.value().contains("EDITOR"), p.value());
        if (viewerAllowed) {
            assertTrue(p.value().contains("VIEWER"), "读端点应放行 VIEWER: " + p.value());
        } else {
            assertTrue(!p.value().contains("VIEWER"), "写端点不得放行 VIEWER: " + p.value());
        }
    }
}
