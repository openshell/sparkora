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
    @Mock BriefService briefService;
    @Mock SearxngSearchTool searxngTool;
    @Mock TavilySearchTool tavilyTool;
    @Mock SettingService settingService;
    @Mock com.sparkora.deep.service.ClarifyConversationService clarifyConversationService;

    private MockMvc mvc;
    private DeepProperties props;

    @BeforeEach
    void setUp() {
        props = new DeepProperties();
        DeepController controller = new DeepController(clarifyService, researchService, writerService,
                briefMapper, briefService, searxngTool, tavilyTool,
                props, settingService, clarifyConversationService);
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

    /** 10-02:research_reasoning 非空 → status 增量透出 planReasoning;缺失不出现该字段(旧契约)。 */
    @Test
    void status_思考过程_增量透出planReasoning() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(9L);
        b.setProjectId(3L);
        b.setGenMode("DEEP");
        b.setResearchReasoning("推理:先分析销量再结论");
        when(briefMapper.selectById(9L)).thenReturn(b);
        when(researchService.resolveSnapshot(9L))
                .thenReturn(WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 9L, 0));

        mvc.perform(get("/api/projects/3/deep/status").param("briefId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planReasoning").value("推理:先分析销量再结论"));

        // 无 reasoning(历史/非推理模型)→ 字段不出现
        ArticleBriefEntity b2 = new ArticleBriefEntity();
        b2.setId(10L);
        b2.setGenMode("DEEP");
        when(briefMapper.selectById(10L)).thenReturn(b2);
        when(researchService.resolveSnapshot(10L))
                .thenReturn(WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 10L, 0));
        mvc.perform(get("/api/projects/3/deep/status").param("briefId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planReasoning").doesNotExist());
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

    // ==================== 09-27-gen-async: /deep/generate 批量异步契约 ====================

    @Test
    void generate_批量styleIds_委托startBatch并返回占位标记() throws Exception {
        when(writerService.startBatch(eq(3L), eq(9L), eq(java.util.List.of(1L, 2L))))
                .thenReturn(Map.of("status", "GENERATING_VERSIONS", "styleCount", 2));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"styleIds\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("GENERATING_VERSIONS"))
                .andExpect(jsonPath("$.data.styleCount").value(2));

        verify(writerService, never()).startBatchLegacy(any(), any(), any(), any());
    }

    @Test
    void generate_单styleId兼容_数组化委托() throws Exception {
        when(writerService.startBatch(eq(3L), eq(9L), eq(java.util.List.of(5L))))
                .thenReturn(Map.of("status", "GENERATING_VERSIONS", "styleCount", 1));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"styleId\":5}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(writerService).startBatch(eq(3L), eq(9L), eq(java.util.List.of(5L)));
    }

    @Test
    void generate_旧stylePrompt兼容_走legacy入口() throws Exception {
        when(writerService.startBatchLegacy(eq(3L), eq(9L), eq("语气活泼"), eq("活泼")))
                .thenReturn(Map.of("status", "GENERATING_VERSIONS", "styleCount", 1));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"stylePrompt\":\"语气活泼\",\"styleName\":\"活泼\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(writerService).startBatchLegacy(eq(3L), eq(9L), eq("语气活泼"), eq("活泼"));
    }

    @Test
    void generate_重复触发_409() throws Exception {
        when(writerService.startBatch(any(), any(), any()))
                .thenThrow(new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）"));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"styleIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void generate_风格查无_400() throws Exception {
        when(writerService.startBatch(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("风格不存在或已删除"));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"styleIds\":[99]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("风格不存在或已删除"));
    }

    @Test
    void generate_未预期异常_500() throws Exception {
        when(writerService.startBatch(any(), any(), any()))
                .thenThrow(new RuntimeException("下游炸了"));

        mvc.perform(post("/api/projects/3/deep/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"styleIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== C1 意图澄清对话接口 ====================

    @Test
    void clarifyStart_成功_返回首题() throws Exception {
        when(clarifyConversationService.start(3L))
                .thenReturn(Map.of("briefId", 9L, "stage", "ASKING", "question", Map.of("id", "purpose")));

        mvc.perform(post("/api/projects/3/deep/clarify/start").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.briefId").value(9))
                .andExpect(jsonPath("$.data.stage").value("ASKING"));
    }

    @Test
    void clarifyStart_并发冲突_409() throws Exception {
        when(clarifyConversationService.start(3L)).thenThrow(new IllegalStateException("已有进行中的意图澄清会话"));

        mvc.perform(post("/api/projects/3/deep/clarify/start").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void clarifyAnswer_收敛_返回TaskBrief() throws Exception {
        when(clarifyConversationService.answer(eq(3L), eq(9L), eq("tone"), eq("专业理性")))
                .thenReturn(Map.of("briefId", 9L, "converged", true, "taskBrief", Map.of("purpose", "目的")));

        mvc.perform(post("/api/projects/3/deep/clarify/answer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"questionId\":\"tone\",\"answer\":\"专业理性\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.converged").value(true))
                .andExpect(jsonPath("$.data.taskBrief.purpose").value("目的"));
    }

    @Test
    void clarifyAnswer_状态冲突_409() throws Exception {
        when(clarifyConversationService.answer(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("会话状态为「CONVERGED」，无法继续回答"));

        mvc.perform(post("/api/projects/3/deep/clarify/answer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9,\"questionId\":\"tone\",\"answer\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void clarifyConverge_brief不存在_400() throws Exception {
        when(clarifyConversationService.converge(3L, 9L)).thenThrow(new IllegalArgumentException("brief 不存在"));

        mvc.perform(post("/api/projects/3/deep/clarify/converge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"briefId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void status_意图澄清增量字段_透出() throws Exception {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(9L);
        b.setGenMode("DEEP");
        b.setClarifyStatus("ASKING");
        b.setClarifySession("{\"status\":\"ASKING\"}");
        b.setTaskBrief("{\"purpose\":{}}");
        when(briefMapper.selectById(9L)).thenReturn(b);
        when(researchService.resolveSnapshot(9L))
                .thenReturn(WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 9L, 0));

        mvc.perform(get("/api/projects/3/deep/status").param("briefId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clarifyStatus").value("ASKING"))
                .andExpect(jsonPath("$.data.clarifySession").exists())
                .andExpect(jsonPath("$.data.taskBrief").exists());
    }
}
