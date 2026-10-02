package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.BriefDto;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BriefService 深度简报链路单测（09-26-deep-research-coverage R6；Mockito 不连库/不调 AI）。
 *
 * 覆盖 generateFromFactSheet 的截断容错：
 *  ① 首次失败(截断 AiException)→ 提额 16384 重试成功 → 简报字段落库 + 项目 READY + currentBriefId；
 *  ② 两次均失败 → 项目回 DRAFT + lastBriefError,且第二次确实用 16384；
 *  ③ 首次即成功 → 只用 8192,不触发 16384 重试。
 *
 * 状态推进自 09-27-state-machine-service 起委托 ProjectStatusService(状态写权收敛):
 * 项目状态断言改为 verify 委托调用与参数,WHERE/SET 逐项等价断言在 ProjectStatusServiceTest。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock AiClient aiClient;
    @Mock ProjectStatusService statusService;

    BriefService service;

    /** 合法完整简报 JSON（字段齐全,可被 BriefDto 反序列化）。 */
    private static final String VALID_BRIEF_JSON = """
            {
              "titleCandidates": ["标题A", "标题B", "标题C"],
              "audienceRefine": "关注新能源的家庭用户",
              "coreViewpoints": ["观点1", "观点2"],
              "outline": [{"heading": "背景", "subPoints": ["要点1", "要点2"]}],
              "factRisks": [{"claim": "续航 700km", "riskLevel": "medium", "suggestion": "发布前核实"}]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new BriefService(projectMapper, briefMapper, aiClient, new ObjectMapper(), statusService);
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("如何看待比亚迪建成第2000座高速闪充站");
        p.setStatus("DRAFT");
        return p;
    }

    private ArticleBriefEntity deepBrief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":[{\"claim\":\"年内建成2万座\",\"value\":\"2万座\"}],\"gaps\":[],\"warnings\":[]}");
        return b;
    }

    private AiClient.ChatResult result() {
        return new AiClient.ChatResult(VALID_BRIEF_JSON, "glm-5.2", 321);
    }

    /** C2:structured 返回 entity + ChatResult;此处按 VALID_BRIEF_JSON 构造 BriefDto。 */
    private AiClient.TypedResult<BriefDto> typed() {
        try {
            BriefDto dto = new ObjectMapper().readValue(VALID_BRIEF_JSON, BriefDto.class);
            return new AiClient.TypedResult<>(dto, result());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 公共桩：项目/简报可查(状态服务 mock 默认无操作即视为抢占/推进成功)。 */
    private void stubHappyPath(ArticleProjectEntity p, ArticleBriefEntity b) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
    }

    /** ① 首次截断(8192 抛 AiException)→ 提额 16384 重试成功 → 字段落库 + READY。 */
    @Test
    void 首次截断_提额重试成功_简报落库且项目READY() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BriefDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length）", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(BriefDto.class))).thenReturn(typed());

        ArticleBriefEntity out = service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);

        // 第二次确实用 16384,且恰好调用两次(净调用上限 2 次)
        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(BriefDto.class));
        verify(aiClient, times(2)).structured(anyString(), anyString(), anyInt(), eq(BriefDto.class));

        // 简报字段落库(同一 DEEP 行)
        ArgumentCaptor<ArticleBriefEntity> briefCaptor = ArgumentCaptor.forClass(ArticleBriefEntity.class);
        verify(briefMapper).updateById(briefCaptor.capture());
        ArticleBriefEntity saved = briefCaptor.getValue();
        assertNotNull(saved.getTitleCandidates(), "标题候选已落库");
        assertTrue(saved.getTitleCandidates().contains("标题A"));
        assertNotNull(saved.getOutline(), "大纲已落库");
        assertNotNull(saved.getFactRisks(), "factRisks 已落库");
        assertEquals("glm-5.2", saved.getAiModel());
        assertEquals(321, saved.getTokenUsage());
        assertEquals(BRIEF_ID, out.getId());

        // 委托状态服务:原子抢占 + 成功推进(仅 GENERATING_BRIEF → READY,current 指向该行)
        verify(statusService).claimBriefGenerating(PROJECT_ID, p, "生成简报");
        verify(statusService).advanceReady(eq(PROJECT_ID), eq(BRIEF_ID), anyMap());
        verify(statusService, never()).failBriefToDraft(any(), anyString());
    }

    /** ② 两次均失败 → 项目回 DRAFT + lastBriefError,且第二次确实用 16384。 */
    @Test
    void 两次均失败_回DRAFT并记录错误_第二次用16384() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BriefDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length）", null));
        when(aiClient.structured(anyString(), anyString(), eq(16384), eq(BriefDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length），重试仍失败", null));

        assertThrows(AiException.class, () -> service.generateFromFactSheet(PROJECT_ID, BRIEF_ID));

        // 恰好两次调用;两次额度分别为 8192 / 16384
        verify(aiClient).structured(anyString(), anyString(), eq(8192), eq(BriefDto.class));
        verify(aiClient).structured(anyString(), anyString(), eq(16384), eq(BriefDto.class));
        verify(aiClient, times(2)).structured(anyString(), anyString(), anyInt(), eq(BriefDto.class));

        // 简报行不动(失败时不写简报字段)
        verify(briefMapper, never()).updateById(any(ArticleBriefEntity.class));

        // 委托状态服务:抢占成功后失败回退 DRAFT + 记录原因(错误列写入与生成中白名单断言在 ProjectStatusServiceTest)
        verify(statusService).claimBriefGenerating(PROJECT_ID, p, "生成简报");
        verify(statusService).failBriefToDraft(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceReady(any(), any(), any());
    }

    /** ③ 首次即成功 → 只用 8192,不触发 16384 重试。 */
    @Test
    void 首次成功_只用8192不重试() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BriefDto.class))).thenReturn(typed());

        ArticleBriefEntity out = service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);

        assertEquals(BRIEF_ID, out.getId());
        verify(aiClient).structured(anyString(), anyString(), eq(8192), eq(BriefDto.class));
        verify(aiClient, never()).structured(anyString(), anyString(), eq(16384), eq(BriefDto.class));
        verify(aiClient, times(1)).structured(anyString(), anyString(), anyInt(), eq(BriefDto.class));
        verify(briefMapper).updateById(any(ArticleBriefEntity.class));
        verify(statusService).advanceReady(eq(PROJECT_ID), eq(BRIEF_ID), anyMap());
    }

    // ==================== R6(09-27-tavily-extract-kind-hypotheses):注入研究假设 ====================

    /** 捕获 user prompt(首次成功的 8192 调用;先清历史调用,支持同一测试多次调用)。 */
    private String capturedUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        stubHappyPath(p, b);
        when(aiClient.structured(anyString(), anyString(), eq(8192), eq(BriefDto.class))).thenReturn(typed());
        service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiClient).structured(anyString(), user.capture(), eq(8192), eq(BriefDto.class));
        String captured = user.getValue();
        org.mockito.Mockito.clearInvocations(aiClient);
        return captured;
    }

    /** research_plan 含 hypotheses → user prompt 注入假设文本。 */
    @Test
    void 研究假设_注入userPrompt() {
        ArticleBriefEntity b = deepBrief();
        b.setResearchPlan("{\"keyQuestions\":[\"背景\"],\"hypotheses\":[\"假设A:该技术将主导市场\",\"假设B:成本将下降\"]}");

        String prompt = capturedUserPrompt(project(), b);

        assertTrue(prompt.contains("研究假设"), "应有研究假设块");
        assertTrue(prompt.contains("假设A:该技术将主导市场"), "假设A应注入");
        assertTrue(prompt.contains("假设B:成本将下降"), "假设B应注入");
    }

    // ==================== 10-02-brief-reasoning-maxtokens R4:创建输入注入简报 prompt ====================

    /** R4:主题 + 内容描述 + 目标读者 + 目标字数 注入简报 user prompt。 */
    @Test
    void 创建输入_注入userPrompt() {
        ArticleProjectEntity p = project();
        p.setContentDescription("围绕第2000座闪充站落成写一篇");
        p.setAudience("汽车行业分析师");
        p.setWordCountTarget(2400);

        String prompt = capturedUserPrompt(p, deepBrief());

        assertTrue(prompt.contains("主题:如何看待比亚迪建成第2000座高速闪充站"), "含主题");
        assertTrue(prompt.contains("内容描述:围绕第2000座闪充站落成写一篇"), "含内容描述");
        assertTrue(prompt.contains("目标读者:汽车行业分析师"), "含目标读者");
        assertTrue(prompt.contains("目标字数:2400"), "含目标字数");
    }

    /** R4:内容描述/目标读者为空 → 不注入对应行;目标字数 null → 默认 1500。 */
    @Test
    void 创建输入_空值不注入_字数默认1500() {
        String prompt = capturedUserPrompt(project(), deepBrief());

        assertFalse(prompt.contains("内容描述:"), "空内容描述不得出现");
        assertFalse(prompt.contains("目标读者:"), "空目标读者不得出现");
        assertTrue(prompt.contains("目标字数:1500"), "null 目标字数应回退 1500");
    }

    /** research_plan 缺失/null/无 hypotheses/畸形 → 不注入且不报错(兼容退化)。 */
    @Test
    void 无研究假设_兼容退化不报错() {
        // null 与空
        assertFalse(capturedUserPrompt(project(), deepBrief()).contains("研究假设"));
        // 无 hypotheses 字段
        ArticleBriefEntity b = deepBrief();
        b.setResearchPlan("{\"keyQuestions\":[\"背景\"]}");
        assertFalse(capturedUserPrompt(project(), b).contains("研究假设"));
        // hypotheses 为空数组
        ArticleBriefEntity b2 = deepBrief();
        b2.setResearchPlan("{\"hypotheses\":[]}");
        assertFalse(capturedUserPrompt(project(), b2).contains("研究假设"));
        // 畸形 JSON → 跳过不抛
        ArticleBriefEntity b3 = deepBrief();
        b3.setResearchPlan("{not-json");
        assertFalse(capturedUserPrompt(project(), b3).contains("研究假设"));
    }
}
