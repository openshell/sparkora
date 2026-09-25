package com.sparkora.web.controller;

import com.sparkora.config.DeepProperties;
import com.sparkora.deep.search.WebProviderOrder;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.deep.service.ClarifyService;
import com.sparkora.deep.service.DeepResearchService;
import com.sparkora.deep.service.DeepWriterService;
import com.sparkora.deep.tool.SearxngSearchTool;
import com.sparkora.deep.tool.TavilySearchTool;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.StyleProfileMapper;
import com.sparkora.service.BriefService;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DeepController HTTP 契约测试(09-25-brief-web-search AC-12/AC-13):
 * /run 前置/互斥错误码(400/409)+ /status 增量策略字段;既有 toolHealth 三键兼容。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepControllerContractTest {

    @Mock ClarifyService clarifyService;
    @Mock DeepResearchService researchService;
    @Mock DeepWriterService writerService;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock BriefService briefService;
    @Mock StyleProfileMapper styleMapper;
    @Mock SearxngSearchTool searxngTool;
    @Mock TavilySearchTool tavilyTool;
    @Mock SettingService settingService;

    private MockMvc mvc;
    private DeepProperties props;

    @BeforeEach
    void setUp() {
        props = new DeepProperties();
        DeepController controller = new DeepController(clarifyService, researchService, writerService,
                briefMapper, projectMapper, briefService, styleMapper, searxngTool, tavilyTool,
                props, settingService);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void run_服务抛参数错误_400() throws Exception {
        when(researchService.run(eq(3L), eq(9L))).thenThrow(new IllegalArgumentException("brief 不属于该项目"));
        mvc.perform(post("/api/projects/3/deep/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("brief 不属于该项目"));
    }

    @Test
    void run_重复触发_409() throws Exception {
        when(researchService.run(any(), any())).thenThrow(new IllegalStateException("该 brief 正在研究中，请勿重复触发"));
        mvc.perform(post("/api/projects/3/deep/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void run_成功_返回策略增量字段() throws Exception {
        when(researchService.run(eq(3L),
                eq(9L))).thenReturn(Map.of("briefId", 9L, "agents", 2, "started", true,
                "strategy", "TAVILY_FIRST", "webProviderOrder", "TAVILY,SEARXNG"));
        mvc.perform(post("/api/projects/3/deep/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.strategy").value("TAVILY_FIRST"));
    }

    @Test
    void status_既有toolHealth三键_兼容且新增策略字段() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(9L);
        b.setProjectId(3L);
        b.setGenMode("DEEP");
        b.setResearchNotes("[{\"agentId\":1,\"status\":\"DONE\"}]");
        when(briefMapper.selectById(9L)).thenReturn(b);
        when(settingService.isKbEnabled()).thenReturn(false);
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        when(tavilyTool.configured()).thenReturn(true);
        when(tavilyTool.lastCallOk()).thenReturn(true);
        when(searxngTool.configured()).thenReturn(true);
        when(searxngTool.lastCallOk()).thenReturn(true);
        when(researchService.resolveSnapshot(9L))
                .thenReturn(WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 9L, 5));

        mvc.perform(get("/api/projects/3/deep/status").param("briefId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.toolHealth.KB").value("DISABLED"))
                .andExpect(jsonPath("$.data.toolHealth.SEARXNG").value("OK"))
                .andExpect(jsonPath("$.data.toolHealth.TAVILY").value("OK"))   // 三键值域兼容
                .andExpect(jsonPath("$.data.webStrategy").value("TAVILY_FIRST"));   // AC-10 增量
    }

    @Test
    void status_开关关闭_工具健康DISABLED() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(9L);
        b.setGenMode("DEEP");
        when(briefMapper.selectById(9L)).thenReturn(b);
        props.setSearchWebEnabled(false);
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        when(researchService.resolveSnapshot(9L))
                .thenReturn(WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 9L, 0));

        mvc.perform(get("/api/projects/3/deep/status").param("briefId", "9"))
                .andExpect(jsonPath("$.data.toolHealth.SEARXNG").value("DISABLED"))
                .andExpect(jsonPath("$.data.toolHealth.TAVILY").value("DISABLED"));
        verify(tavilyTool, never()).search(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
