package com.sparkora.service;

import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.web.dto.BydActivityGenerateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 王朝小程序「官方活动发文」生成服务单测（10-19-channel-editor-publish R4）。
 *
 * <p>覆盖：渠道门禁、必填校验、生成中互斥、成功建新版本并推进状态、失败回写中文错误。
 * {@code @Async} 只在 Spring 代理生效；单测直接调用 {@code runGenerate} 验证业务体。</p>
 */
@ExtendWith(MockitoExtension.class)
class BydDynastyGenerateServiceTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock AiClient aiClient;
    @Mock ProjectStatusService statusService;

    BydDynastyGenerateService service;

    @BeforeEach
    void setUp() {
        service = new BydDynastyGenerateService(projectMapper, versionMapper, aiClient, statusService);
    }

    private static ArticleProjectEntity project(String channel) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(66L);
        p.setChannel(channel);
        p.setStatus("DRAFT");
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    private static BydActivityGenerateRequest req() {
        BydActivityGenerateRequest r = new BydActivityGenerateRequest();
        r.setActivityBrief("王朝车友会春日故事征集");
        r.setPersonalInfo("汉 DM-i 车主，成都到稻城亚丁自驾");
        return r;
    }

    @Test
    void startRejectsBlankFieldsBeforeTouchingProject() {
        BydActivityGenerateRequest blank = new BydActivityGenerateRequest();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.startGenerate(66L, blank, 1L));
        assertEquals("官方活动要求不能为空", e.getMessage());
        blank.setActivityBrief("活动");
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> service.startGenerate(66L, blank, 1L));
        assertEquals("我的情况不能为空", e2.getMessage());
        verify(projectMapper, never()).selectById(any());
    }

    @Test
    void startRejectsGzhProject() {
        when(projectMapper.selectById(66L)).thenReturn(project("gzh"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.startGenerate(66L, req(), 1L));
        assertEquals("该项目不是王朝小程序渠道项目", e.getMessage());
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void startRejectsWhileGenerating() {
        ArticleProjectEntity p = project("byd_miniapp");
        when(projectMapper.selectById(66L)).thenReturn(p);
        when(statusService.stuckGenerating(p)).thenReturn(true);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startGenerate(66L, req(), 1L));
        assertTrue(e.getMessage().contains("正在生成中"));
        verify(statusService, never()).claimDeepVersionsGenerating(any(), any(), anyString());
    }

    @Test
    void runGenerateCreatesNewVersionAndAdvancesStatus() {
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(BydDynastyGenerateService.BydDynastyDraft.class)))
                .thenReturn(new AiClient.TypedResult<>(
                        new BydDynastyGenerateService.BydDynastyDraft("成都到稻城亚丁", "第一段正文\n\n第二段正文"),
                        new AiClient.ChatResult("{}", "mock-model", 42, "stop")));
        doAnswer(inv -> {
            ArticleVersionEntity v = inv.getArgument(0);
            v.setId(100L);
            return 1;
        }).when(versionMapper).insert(any(ArticleVersionEntity.class));
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        service.runGenerate(66L, req());

        ArgumentCaptor<ArticleVersionEntity> captor = ArgumentCaptor.forClass(ArticleVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        ArticleVersionEntity saved = captor.getValue();
        assertEquals(66L, saved.getProjectId());
        assertEquals("成都到稻城亚丁", saved.getTitle());
        assertEquals("第一段正文\n\n第二段正文", saved.getContentMd());
        assertEquals("BYD", saved.getVersionLabel());
        verify(statusService).advanceVersionsReady(eq(66L), eq(100L), isNull());
        verify(statusService, never()).failVersionsToReady(any(), anyString());
    }

    @Test
    void runGenerateFallsBackToLastVersionErrorOnFailure() {
        when(aiClient.structured(anyString(), anyString(), anyInt(), eq(BydDynastyGenerateService.BydDynastyDraft.class)))
                .thenThrow(new IllegalStateException("AI 调用失败"));

        service.runGenerate(66L, req());

        verify(versionMapper, never()).insert(any(ArticleVersionEntity.class));
        verify(statusService).failVersionsToReady(eq(66L), eq("AI 调用失败"));
        verify(statusService, never()).advanceVersionsReady(any(), any(), any());
    }

    @Test
    void stateReportsGeneratingAndCurrentVersion() {
        ArticleProjectEntity p = project("byd_miniapp");
        p.setCurrentVersionId(49L);
        p.setLastVersionError("上次失败");
        when(projectMapper.selectById(66L)).thenReturn(p);
        when(statusService.stuckGenerating(p)).thenReturn(true);

        var out = service.state(66L);

        assertEquals(true, out.get("generating"));
        assertEquals(49L, out.get("currentVersionId"));
        assertEquals("上次失败", out.get("lastError"));
    }
}
