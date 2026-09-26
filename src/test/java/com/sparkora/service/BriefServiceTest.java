package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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

        // 项目状态机推到 READY + currentBriefId 指向该行 + 清空旧错误。
        // R2(09-27-p0-hardening):由 updateById 全字段回写改为 UpdateWrapper 条件更新,断言 set 列与取值。
        // 项目表共 2 次 update:第 1 次原子抢占置 GENERATING_BRIEF,第 2 次(本断言)推进 READY。
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> uwCaptor = updateWrapperCaptor();
        verify(projectMapper, times(2)).update(isNull(), uwCaptor.capture());
        UpdateWrapper<ArticleProjectEntity> uw = uwCaptor.getAllValues().get(1);
        assertTrue(uw.getSqlSet().contains("status"), "set 含 status");
        assertTrue(uw.getSqlSet().contains("current_brief_id"), "set 含 current_brief_id");
        assertTrue(uw.getSqlSet().contains("last_brief_error"), "set 含 last_brief_error");
        assertTrue(uw.getSqlSegment().contains("status"), "WHERE 带状态白名单");
        assertTrue(uw.getParamNameValuePairs().values().contains("READY"), "推进到 READY");
        assertTrue(uw.getParamNameValuePairs().values().contains(BRIEF_ID), "currentBriefId 指向该行");
        assertTrue(uw.getParamNameValuePairs().values().contains("GENERATING_BRIEF"), "仅从生成中状态推进");
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

        // 项目回 DRAFT + 记录失败原因（R2: 条件更新,断言 set 取值;2 次 update 的第 2 次为失败回退）
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> uwCaptor = updateWrapperCaptor();
        verify(projectMapper, times(2)).update(isNull(), uwCaptor.capture());
        UpdateWrapper<ArticleProjectEntity> uw = uwCaptor.getAllValues().get(1);
        String sqlSet = uw.getSqlSet();
        String sqlSegment = uw.getSqlSegment();  // 先物化 WHERE,paramNameValuePairs 才含条件值
        assertTrue(sqlSet.contains("status"), "set 含 status");
        assertTrue(sqlSet.contains("last_brief_error"), "set 含 last_brief_error");
        assertTrue(sqlSegment.contains("status"), "WHERE 限定状态");
        assertTrue(uw.getParamNameValuePairs().values().contains("DRAFT"), "回退到 DRAFT");
        assertTrue(uw.getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.contains("截断")), "失败原因已落库");
        assertTrue(uw.getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")), "失败回退限定生成中状态");
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

    /** 捕获项目表 UpdateWrapper（R2 起项目写入一律走 UpdateWrapper 条件更新,不再 updateById 全字段回写）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
    }
}
