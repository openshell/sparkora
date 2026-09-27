package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepWriterService 正文提额重试单测(09-27-brief-writing-linkage-fix R4/AC-04)。
 *
 * <p>首次 4096 失败(截断/异常)→ 提额 8192 重试一次并成功落版本;首次成功仅调 1 次、不重试;
 * 两次均被截断 → 抛 AiException(使 runBatch 计入失败),且不落版本。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepWriterServiceRetryTest {

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
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(BRIEF_ID);
        b.setProjectId(PROJECT_ID);
        b.setGenMode("DEEP");
        b.setFactSheet("{\"entries\":[{\"key\":\"价格\",\"value\":\"239900\",\"confidence\":0.9}]}");
        when(briefMapper.selectById(BRIEF_ID)).thenReturn(b);
        doAnswer(inv -> { ((ArticleVersionEntity) inv.getArgument(0)).setId(11L); return 1; })
                .when(versionMapper).insert(any(ArticleVersionEntity.class));
    }

    @Test
    void 首次截断_提额8192重试并落版本() throws Exception {
        // 首次 finish_reason=length(内容仍返回,不抛);第二次正常
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("半截正文", "m", 10, "length"))
                .thenReturn(new AiClient.ChatResult("完整正文", "m", 20, "stop"));

        Long id = service.write(PROJECT_ID, BRIEF_ID, "", "深度");

        org.junit.jupiter.api.Assertions.assertEquals(11L, id);
        verify(aiClient).chat(anyString(), anyString(), eq(4096));
        verify(aiClient).chat(anyString(), anyString(), eq(8192));
        verify(versionMapper, times(1)).insert(any(ArticleVersionEntity.class));   // 重试不重复落库
    }

    @Test
    void 首次异常_提额8192重试成功() throws Exception {
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("AI 超时", null))
                .thenReturn(new AiClient.ChatResult("完整正文", "m", 20, "stop"));

        service.write(PROJECT_ID, BRIEF_ID, "", "深度");

        verify(aiClient).chat(anyString(), anyString(), eq(8192));
        verify(versionMapper, times(1)).insert(any(ArticleVersionEntity.class));
    }

    @Test
    void 首次成功_不触发重试() throws Exception {
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("完整正文", "m", 20, "stop"));

        service.write(PROJECT_ID, BRIEF_ID, "", "深度");

        verify(aiClient, times(1)).chat(anyString(), anyString(), anyInt());
        verify(aiClient, never()).chat(anyString(), anyString(), eq(8192));
    }

    @Test
    void 两次均截断_抛异常且不落版本() throws Exception {
        when(aiClient.chat(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("半截一", "m", 10, "length"))
                .thenReturn(new AiClient.ChatResult("半截二", "m", 10, "length"));

        assertThrows(AiException.class, () -> service.write(PROJECT_ID, BRIEF_ID, "", "深度"));
        verify(aiClient).chat(anyString(), anyString(), eq(8192));
        verify(versionMapper, never()).insert(any(ArticleVersionEntity.class));
    }
}
