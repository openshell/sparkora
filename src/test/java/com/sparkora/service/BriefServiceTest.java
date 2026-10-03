package com.sparkora.service;

import com.sparkora.ai.AiException;
import com.sparkora.deep.service.BlueprintService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BriefService 深度简报链路单测（C3 委托改造后；Mockito 不连库/不调 AI）。
 *
 * <p>C3（10-03-gen-cognitive-redesign）起认知产物（写作蓝图）由 {@link BlueprintService} 生成，
 * 本服务只保留状态机守护与事务边界：校验/抢占/推进/回退委托，成功时委托蓝图服务并推进 READY，
 * 失败时回 DRAFT + 记录错误并抛 {@link AiException}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ProjectStatusService statusService;
    @Mock BlueprintService blueprintService;

    BriefService service;

    @BeforeEach
    void setUp() {
        service = new BriefService(projectMapper, briefMapper, statusService, blueprintService);
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
        b.setFactSheet("{\"entries\":[{\"key\":\"年内建成2万座\",\"value\":\"2万座\"}],\"gaps\":[],\"warnings\":[]}");
        return b;
    }

    private void stubHappyPath(ArticleProjectEntity p, ArticleBriefEntity b) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
    }

    /** 成功：委托 BlueprintService 生成蓝图，并推进 READY；不直接写 brief 旧字段。 */
    @Test
    void 成功_委托蓝图服务_推进READY() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        ArticleBriefEntity blueprintResult = deepBrief();
        blueprintResult.setWritingBlueprint("{\"thesis\":\"论点\"}");
        blueprintResult.setBlueprintStatus("REVIEWING");
        when(blueprintService.generate(PROJECT_ID, BRIEF_ID)).thenReturn(blueprintResult);

        ArticleBriefEntity out = service.generateFromFactSheet(PROJECT_ID, BRIEF_ID);

        verify(blueprintService).generate(PROJECT_ID, BRIEF_ID);
        verify(statusService).claimBriefGenerating(PROJECT_ID, p, "生成简报");
        verify(statusService).advanceReady(eq(PROJECT_ID), eq(BRIEF_ID), anyMap());
        verify(statusService, never()).failBriefToDraft(any(), anyString());
        // C3:不再直接 updateById 旧简报字段
        verify(briefMapper, never()).updateById(any(ArticleBriefEntity.class));
        assertEquals("REVIEWING", out.getBlueprintStatus());
    }

    /** 失败：蓝图服务抛异常 → 回 DRAFT 记录错误 + 抛 AiException。 */
    @Test
    void 蓝图服务失败_回DRAFT并抛AiException() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        stubHappyPath(p, b);
        when(blueprintService.generate(PROJECT_ID, BRIEF_ID))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null));

        assertThrows(AiException.class, () -> service.generateFromFactSheet(PROJECT_ID, BRIEF_ID));

        verify(statusService).claimBriefGenerating(PROJECT_ID, p, "生成简报");
        verify(statusService).failBriefToDraft(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceReady(any(), any(), any());
    }

    /** 前置：事实手册为空 → IllegalState（不进入抢占/委托）。 */
    @Test
    void 事实手册为空_抛IllegalState() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        b.setFactSheet(null);
        stubHappyPath(p, b);

        assertThrows(IllegalStateException.class, () -> service.generateFromFactSheet(PROJECT_ID, BRIEF_ID));
        verify(blueprintService, never()).generate(any(), any());
        verify(statusService, never()).claimBriefGenerating(any(), any(), anyString());
    }

    /** 前置：非 DEEP brief → IllegalArgument。 */
    @Test
    void 非DEEP_brief_抛IllegalArgument() {
        ArticleProjectEntity p = project();
        ArticleBriefEntity b = deepBrief();
        b.setGenMode("FAST");
        stubHappyPath(p, b);

        assertThrows(IllegalArgumentException.class, () -> service.generateFromFactSheet(PROJECT_ID, BRIEF_ID));
        verify(blueprintService, never()).generate(any(), any());
    }
}
