package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.ChannelDTO;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.SourceContentDTO;
import com.sparkora.domain.dto.SourceCreateDTO;
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

    // ==================== G4 信源注册闭环(10-09) ====================

    @Test
    void 新建信源_携带channels_创建并回读channels数组() throws Exception {
        SourceEntity created = new SourceEntity();
        created.setId(20L);
        created.setName("乘联会");
        created.setType("SITE");
        SourceChannelEntity c1 = new SourceChannelEntity();
        c1.setId(30L);
        c1.setName("车市解读");
        c1.setCategory("销量数据");
        created.setChannels(List.of(c1));
        when(sourceService.create(any(SourceCreateDTO.class))).thenReturn(created);

        mvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"乘联会\",\"type\":\"SITE\","
                                + "\"channels\":[{\"name\":\"车市解读\",\"listUrl\":\"https://www.cpcaauto.com/news.php?types=csjd\","
                                + "\"detailBaseUrl\":\"https://www.cpcaauto.com\",\"category\":\"销量数据\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(20))
                .andExpect(jsonPath("$.data.channels.length()").value(1))
                .andExpect(jsonPath("$.data.channels[0].name").value("车市解读"));
    }

    @Test
    void 新建信源_type非法_400() throws Exception {
        when(sourceService.create(any(SourceCreateDTO.class)))
                .thenThrow(new IllegalArgumentException("类型必须是 RSS 或 SITE"));

        mvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void 新增栏目_返回栏目() throws Exception {
        SourceChannelEntity c = new SourceChannelEntity();
        c.setId(31L);
        c.setName("销量排行");
        c.setSourceId(20L);
        when(sourceService.addChannel(eq(20L), any(ChannelDTO.class))).thenReturn(c);

        mvc.perform(post("/api/sources/20/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"销量排行\",\"listUrl\":\"https://auto.gasgoo.com/qcxl\","
                                + "\"parseRules\":\"{\\\"listRows\\\":\\\"div.data ul li\\\"}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(31))
                .andExpect(jsonPath("$.data.name").value("销量排行"));
    }

    @Test
    void 编辑栏目_200() throws Exception {
        SourceChannelEntity c = new SourceChannelEntity();
        c.setId(31L);
        c.setEnabled(false);
        when(sourceService.updateChannel(eq(31L), any(ChannelDTO.class))).thenReturn(c);

        mvc.perform(put("/api/channels/31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void 删除栏目_返回ok() throws Exception {
        when(sourceService.deleteChannel(31L)).thenReturn(true);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/channels/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    void 删除栏目_不存在404() throws Exception {
        when(sourceService.deleteChannel(999L)).thenThrow(new IllegalArgumentException("栏目不存在"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/channels/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== G6 计划可视化(10-09,R11/AC-11) ====================

    @Test
    void 信源列表_含nextRunAt字段() throws Exception {
        SourceEntity s = new SourceEntity();
        s.setId(5L);
        s.setName("乘联会");
        s.setEnabled(true);
        s.setNextRunAt(LocalDateTime.of(2026, 11, 8, 3, 30));
        when(sourceService.list()).thenReturn(List.of(s));

        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nextRunAt").value("2026-11-08T03:30:00"));
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
        assertRoles(pa("rebuildContent", Long.class), false);   // 10-05 E 向量重建(写端点)
        // 10-09 G4 注册闭环(写端点)
        assertRoles(pa("create", SourceCreateDTO.class), false);
        assertRoles(pa("addChannel", Long.class, ChannelDTO.class), false);
        assertRoles(pa("updateChannel", Long.class, ChannelDTO.class), false);
        assertRoles(pa("deleteChannel", Long.class), false);
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
