package com.sparkora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.StyleProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImitationService 异步切分单测(09-27-gen-async AC7,Mockito 不连库/不调 AI)。
 *
 * <p>自注入代理在单测直 new 场景为 null,`self == null ? this : self` 回退为同步执行,
 * 因此可直接断言 start 同步阶段(claim/返回占位/校验)与 run 异步体的成功/失败落状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImitationServiceAsyncTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock StyleProfileMapper styleMapper;
    @Mock AiClient aiClient;
    @Mock ProjectStatusService statusService;

    ImitationService service;

    private static final String VALID_ANALYZE_JSON = """
            {"genre":"评测","structure":"痛点→论证","sentenceFeatures":"短句",
             "titleCandidates":["A","B","C"],"coreViewpoints":["v1"],"outline":[{"heading":"h","subPoints":["p"]}],
             "recommendations":[]}
            """;

    @BeforeEach
    void setUp() {
        service = new ImitationService(projectMapper, briefMapper, styleMapper, aiClient, new ObjectMapper(), statusService);
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setGenSource("IMITATION");
        p.setImitationText("参考原文内容");
        p.setStatus("DRAFT");
        return p;
    }

    @Test
    void 同步阶段_claim后立即返回占位标记_不阻塞AI() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(styleMapper.selectList(any())).thenReturn(List.of());
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VALID_ANALYZE_JSON, "m", 1));
        doAnswer(inv -> { ((ArticleBriefEntity) inv.getArgument(0)).setId(BRIEF_ID); return 1; })
                .when(briefMapper).insert(any(ArticleBriefEntity.class));

        Map<String, Object> out = service.analyze(PROJECT_ID);

        assertEquals("GENERATING_BRIEF", out.get("status"), "同步阶段返回占位标记");
        verify(statusService).claimBriefGenerating(PROJECT_ID, project(), "分析原文");
        // self==null → 同步执行 runAnalyze:成功推进 READY + imitation_analysis 业务列
        verify(statusService).advanceReady(eq(PROJECT_ID), eq(BRIEF_ID), anyMap());
        verify(statusService, never()).failBriefToDraft(any(), anyString());
    }

    @Test
    void 异步体失败_回DRAFT写错误_不外抛() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(styleMapper.selectList(any())).thenReturn(List.of());
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("模型超时", null));

        // 异步体不抛异常(无调用方),失败只落状态
        service.analyze(PROJECT_ID);

        verify(statusService).claimBriefGenerating(PROJECT_ID, project(), "分析原文");
        verify(statusService).failBriefToDraft(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceReady(any(), any(), any());
    }

    @Test
    void 同步阶段校验失败_未claim不触发异步() {
        ArticleProjectEntity p = project();
        p.setImitationText("  ");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class, () -> service.analyze(PROJECT_ID));
        verify(statusService, never()).claimBriefGenerating(any(), any(), anyString());
    }

    @Test
    void claim冲突_409语义外抛() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        doThrow(new IllegalStateException("该项目正在生成中")).when(statusService)
                .claimBriefGenerating(PROJECT_ID, project(), "分析原文");

        assertThrows(IllegalStateException.class, () -> service.analyze(PROJECT_ID));
        verify(statusService, never()).advanceReady(any(), any(), any());
        verify(briefMapper, never()).insert(any(ArticleBriefEntity.class));
    }
}
