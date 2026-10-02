package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.car.service.CarModelService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.service.ProjectStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClarifyService 研究计划生成单测(10-02-brief-reasoning-maxtokens R1/R2/R4)。
 *
 * <p>直接调用 {@code runAsync}(单测无代理,等效同步)断言:
 *  - 澄清 prompt 注入 内容描述 + 目标读者 + 目标字数(AC5);
 *  - 首次失败(截断)→ 提额 16384 重试一次(AC2);
 *  - 成功后 research_reasoning 落库(AC3)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClarifyServicePlanTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long BRIEF_ID = 7L;

    @Mock AiClient aiClient;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock CarModelService carModelService;
    @Mock ProjectStatusService statusService;

    ClarifyService service;

    @BeforeEach
    void setUp() {
        service = new ClarifyService(aiClient, new ObjectMapper(), briefMapper, projectMapper,
                carModelService, statusService);
        when(carModelService.list()).thenReturn(java.util.List.of());
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("比亚迪9月销量发布");
        p.setContentDescription("围绕第2000座闪充站落成写一篇");
        p.setAudience("汽车行业分析师");
        p.setWordCountTarget(2200);
        return p;
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setPlanStatus("PLANNING");
        return b;
    }

    private static final String PLAN_JSON = "{\"keyQuestions\":[\"销量如何\"],\"dataNeeds\":[],"
            + "\"hypotheses\":[],\"toolHints\":[],\"questions\":[{\"q\":\"读者是谁\",\"type\":\"input\"}]}";

    @Test
    void 澄清prompt注入内容描述_目标读者_目标字数() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(PLAN_JSON, "m", 10, "stop", "思考过程"));

        service.runAsync(BRIEF_ID, PROJECT_ID);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chatJson(sys.capture(), user.capture(), eq(8192));
        assertTrue(user.getValue().contains("主题:比亚迪9月销量发布"), "含主题");
        assertTrue(user.getValue().contains("内容描述:围绕第2000座闪充站落成写一篇"), "含内容描述");
        assertTrue(user.getValue().contains("目标读者:汽车行业分析师"), "含目标读者");
        assertTrue(user.getValue().contains("目标字数:2200"), "含目标字数");
    }

    @Test
    void 首次截断_提额16384重试成功() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null))
                .thenReturn(new AiClient.ChatResult(PLAN_JSON, "m", 10, "stop", "思考过程"));

        service.runAsync(BRIEF_ID, PROJECT_ID);

        verify(aiClient).chatJson(anyString(), anyString(), eq(16384));
        verify(statusService).writeBriefError(eq(PROJECT_ID), eq(null));   // 成功清空错误
    }

    @Test
    void 成功后researchReasoning落库() throws Exception {
        ArticleBriefEntity b = brief();
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(PLAN_JSON, "m", 10, "stop", "推理:先分析销量再结论"));

        service.runAsync(BRIEF_ID, PROJECT_ID);

        ArgumentCaptor<ArticleBriefEntity> saved = ArgumentCaptor.forClass(ArticleBriefEntity.class);
        verify(briefMapper).updateById(saved.capture());
        assertEquals("推理:先分析销量再结论", saved.getValue().getResearchReasoning(), "思考过程应落库");
        assertEquals("READY", saved.getValue().getPlanStatus());
    }
}
