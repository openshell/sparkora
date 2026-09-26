package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock AiClient aiClient;

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
        service = new BriefService(projectMapper, briefMapper, aiClient, new ObjectMapper());
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

    /** 公共桩：项目/简报可查,原子抢占成功。 */
    private void stubHappyPath(ArticleProjectEntity p, ArticleBriefEntity b) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        when(projectMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    /** ① 首次截断(8192 抛 AiException)→ 提额 16384 重试成功 → 字段落库 + READY。 */
    @Test
    void 首次截断_提额重试成功_简报落库且项目READY() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.chatJson(anyString(), anyString(), eq(8192)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length）", null));
        when(aiClient.chatJson(anyString(), anyString(), eq(16384))).thenReturn(result());

        ArticleBriefEntity out = service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);

        // 第二次确实用 16384,且恰好调用两次(净调用上限 2 次)
        verify(aiClient).chatJson(anyString(), anyString(), eq(16384));
        verify(aiClient, times(2)).chatJson(anyString(), anyString(), anyInt());

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

        // 项目状态机推到 READY + currentBriefId 指向该行 + 清空旧错误
        ArgumentCaptor<ArticleProjectEntity> projectCaptor = ArgumentCaptor.forClass(ArticleProjectEntity.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        ArticleProjectEntity savedProject = projectCaptor.getValue();
        assertEquals("READY", savedProject.getStatus());
        assertEquals(BRIEF_ID, savedProject.getCurrentBriefId());
        assertNull(savedProject.getLastBriefError(), "成功后清空旧错误");
    }

    /** ② 两次均失败 → 项目回 DRAFT + lastBriefError,且第二次确实用 16384。 */
    @Test
    void 两次均失败_回DRAFT并记录错误_第二次用16384() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.chatJson(anyString(), anyString(), eq(8192)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length）", null));
        when(aiClient.chatJson(anyString(), anyString(), eq(16384)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断（finish_reason=length），重试仍失败", null));

        assertThrows(AiException.class, () -> service.generateFromFactSheet(PROJECT_ID, BRIEF_ID));

        // 恰好两次调用;两次额度分别为 8192 / 16384
        verify(aiClient).chatJson(anyString(), anyString(), eq(8192));
        verify(aiClient).chatJson(anyString(), anyString(), eq(16384));
        verify(aiClient, times(2)).chatJson(anyString(), anyString(), anyInt());

        // 简报行不动(失败时不写简报字段)
        verify(briefMapper, never()).updateById(any(ArticleBriefEntity.class));

        // 项目回 DRAFT + 记录失败原因
        ArgumentCaptor<ArticleProjectEntity> projectCaptor = ArgumentCaptor.forClass(ArticleProjectEntity.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        ArticleProjectEntity savedProject = projectCaptor.getValue();
        assertEquals("DRAFT", savedProject.getStatus());
        assertNotNull(savedProject.getLastBriefError(), "失败原因已落库");
        assertTrue(savedProject.getLastBriefError().contains("截断"));
    }

    /** ③ 首次即成功 → 只用 8192,不触发 16384 重试。 */
    @Test
    void 首次成功_只用8192不重试() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(aiClient.chatJson(anyString(), anyString(), eq(8192))).thenReturn(result());

        ArticleBriefEntity out = service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);

        assertEquals(BRIEF_ID, out.getId());
        verify(aiClient).chatJson(anyString(), anyString(), eq(8192));
        verify(aiClient, never()).chatJson(anyString(), anyString(), eq(16384));
        verify(aiClient, times(1)).chatJson(anyString(), anyString(), anyInt());
        verify(briefMapper).updateById(any(ArticleBriefEntity.class));
    }
}
