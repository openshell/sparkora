package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.StyleProfileMapper;
import com.sparkora.service.ProjectStatusService;
import com.sparkora.service.SettingService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepWriterService 批量异步切分单测(09-27-gen-async AC7,Mockito 不连库/不调 AI)。
 *
 * <p>自注入代理在单测直 new 场景为 null,`self == null ? this : self` 回退为同步执行,
 * 可直接断言同步阶段(claim/占位/校验)与 runBatch 的成功/部分失败/全失败落状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepWriterServiceBatchTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock AiClient aiClient;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock SettingService settingService;
    @Mock StyleProfileMapper styleMapper;
    @Mock ProjectStatusService statusService;

    DeepWriterService service;

    @BeforeEach
    void setUp() {
        service = new DeepWriterService(aiClient, new ObjectMapper(), briefMapper, versionMapper,
                projectMapper, settingService, styleMapper, statusService);
        when(settingService.isKbEnabled()).thenReturn(true);
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":[{\"key\":\"价格\",\"value\":\"239900\",\"confidence\":0.9}]}");
        return b;
    }

    private ArticleProjectEntity project(String status) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("主题");
        p.setStatus(status);
        return p;
    }

    private StyleProfileEntity style(Long id, String name) {
        StyleProfileEntity s = new StyleProfileEntity();
        s.setId(id);
        s.setName(name);
        s.setToneGuidance("语气" + name);
        return s;
    }

    @Test
    void startBatch_同步claim并返回占位_随后推进VERSIONS_READY() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("正文内容", "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(101L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        Map<String, Object> out = service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L, 2L));

        assertEquals("GENERATING_VERSIONS", out.get("status"));
        assertEquals(2, out.get("styleCount"));
        verify(statusService).claimDeepVersionsGenerating(eq(PROJECT_ID), any(), eq("生成版本"));
        verify(versionMapper, times(2)).insert(any(ArticleVersionEntity.class));
        verify(statusService).advanceVersionsReady(PROJECT_ID, 101L, null);
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void startBatch_部分失败_推进并写last_version_error() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("正文内容", "m", 10))
                .thenThrow(new AiException("AI 超时", null));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(201L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L, 2L));

        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(201L), contains("[B:活泼]"));
    }

    @Test
    void startBatch_全部失败_failVersionsToReady不重复推进() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("READY"));
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        when(aiClient.chat(anyString(), anyString(), anyInt())).thenThrow(new AiException("AI 超时", null));

        service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L));

        verify(statusService).failVersionsToReady(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceVersionsReady(any(), any(), any());
        verify(versionMapper, never()).insert(any(ArticleVersionEntity.class));
    }

    @Test
    void startBatch_风格查无_400且未claim() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(99L))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.startBatch(PROJECT_ID, BRIEF_ID, List.of(99L)));
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void startBatch_brief不属于项目_400() {
        ArticleBriefEntity b = brief();
        b.setProjectId(999L);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);

        assertThrows(IllegalArgumentException.class,
                () -> service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L)));
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void startBatch_claim冲突409_不触发AI() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("VERSIONS_READY"));
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        doThrow(new IllegalStateException("该项目正在生成中")).when(statusService)
                .claimDeepVersionsGenerating(eq(PROJECT_ID), any(), eq("生成版本"));

        assertThrows(IllegalStateException.class,
                () -> service.startBatch(PROJECT_ID, BRIEF_ID, List.of(1L)));
        verify(aiClient, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    void startBatch_无风格_生成单版默认深度() {
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("DRAFT"));
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("正文内容", "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(301L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        Map<String, Object> out = service.startBatch(PROJECT_ID, BRIEF_ID, null);

        assertEquals(1, out.get("styleCount"));
        verify(versionMapper).insert(any(ArticleVersionEntity.class));
        verify(statusService).advanceVersionsReady(PROJECT_ID, 301L, null);
    }
}
