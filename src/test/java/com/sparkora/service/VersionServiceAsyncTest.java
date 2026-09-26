package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VersionService 异步切分单测(09-27-gen-async AC7,Mockito 不连库/不调 AI)。
 *
 * <p>自注入代理在单测直 new 场景为 null,`self == null ? this : self` 回退为同步执行,
 * 因此可直接断言同步阶段(claim/占位/校验/410 由控制器判定)与异步体的成功/部分失败/全失败落状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionServiceAsyncTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleBriefMapper briefMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock StyleProfileMapper styleMapper;
    @Mock AiClient aiClient;
    @Mock CarRagService ragService;
    @Mock ArticleProjectCarService carService;
    @Mock ImitationService imitationService;
    @Mock ProjectStatusService statusService;

    VersionService service;

    private static final String VERSION_JSON = "{\"title\":\"标题\",\"contentMd\":\"# 正文\\n内容\"}";

    @BeforeEach
    void setUp() {
        service = new VersionService(projectMapper, briefMapper, versionMapper, styleMapper,
                aiClient, ragService, carService, new ObjectMapper(), imitationService, statusService);
        when(carService.listModelIds(PROJECT_ID)).thenReturn(List.of());
        when(ragService.retrieveForGeneration(any(), anyInt(), any())).thenReturn(CarRagService.RagResult.EMPTY);
    }

    private ArticleProjectEntity project(String genSource, String status) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setTopic("主题");
        p.setCurrentBriefId(BRIEF_ID);
        p.setGenSource(genSource);
        p.setStatus(status);
        return p;
    }

    private ArticleBriefEntity brief() {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("IMITATION");
        return b;
    }

    private StyleProfileEntity style(Long id, String name) {
        StyleProfileEntity s = new StyleProfileEntity();
        s.setId(id);
        s.setName(name);
        s.setToneGuidance("语气" + name);
        return s;
    }

    @Test
    void 仿写同步阶段_claim后返回占位并推进VERSIONS_READY() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(101L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        Map<String, Object> out = service.generate(PROJECT_ID, List.of(1L, 2L));

        assertEquals("GENERATING_VERSIONS", out.get("status"));
        assertEquals(2, out.get("styleCount"));
        verify(statusService).claimVersionsGenerating(PROJECT_ID, project("IMITATION", "READY"), "生成版本");
        // 两风格两版,首版 id 101
        verify(versionMapper, times(2)).insert(any(ArticleVersionEntity.class));
        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(101L), eq(null));
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void 仿写部分失败_记录last_version_error但状态仍推进() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        // 第一风格成功,第二风格 AI 抛错
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10))
                .thenThrow(new AiException("AI 超时", null));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(201L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.generate(PROJECT_ID, List.of(1L, 2L));

        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(201L), org.mockito.ArgumentMatchers.contains("[B:活泼]"));
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void 仿写全部失败_failVersionsToReady回READY() {
        ArticleProjectEntity p = project("IMITATION", "READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(style(1L, "正式"), style(2L, "活泼")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("AI 超时", null));

        service.generate(PROJECT_ID, List.of(1L, 2L));

        verify(statusService).failVersionsToReady(eq(PROJECT_ID), anyString());
        verify(statusService, never()).advanceVersionsReady(any(), any(), any());
    }

    @Test
    void 同步阶段参数校验_空风格400_未claim() {
        assertThrows(IllegalArgumentException.class, () -> service.generate(PROJECT_ID, List.of()));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void 同步阶段超过10风格400() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList();
        assertThrows(IllegalArgumentException.class, () -> service.generate(PROJECT_ID, ids));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void 同步阶段brief未就绪409_未claim() {
        ArticleProjectEntity p = project("IMITATION", "DRAFT");
        p.setCurrentBriefId(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        assertThrows(NotReadyException.class, () -> service.generate(PROJECT_ID, List.of(1L)));
        verify(statusService, never()).claimVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void claim冲突409_不触发AI() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("IMITATION", "READY"));
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(style(1L, "正式")));
        doThrow(new IllegalStateException("该项目正在生成中")).when(statusService)
                .claimVersionsGenerating(eq(PROJECT_ID), any(), eq("生成版本"));

        assertThrows(IllegalStateException.class, () -> service.generate(PROJECT_ID, List.of(1L)));
        verify(aiClient, never()).chatJson(anyString(), anyString(), anyInt());
    }

    @Test
    void 追加生成_现有current时advance保留用户选择() {
        // advanceVersionsReady 的首版两拆分语义在 ProjectStatusServiceTest 断言;此处只验证委托参数
        ArticleProjectEntity p = project("IMITATION", "VERSIONS_READY");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
        when(styleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(style(3L, "干货")));
        when(aiClient.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult(VERSION_JSON, "m", 10));
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(301L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));

        service.generate(PROJECT_ID, List.of(3L));

        verify(statusService).advanceVersionsReady(eq(PROJECT_ID), eq(301L), eq(null));
    }
}
