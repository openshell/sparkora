package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeepResearchService.run 前置校验与运行互斥单测(09-25-brief-web-search R6/AC-05/AC-13)。
 * 用 Mockito 桩替代 self 代理,验证「重复 /run 返回 409 语义」是真实生效的 service 层行为,
 * 而非仅控制器异常映射。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchServiceRunTest {

    @Mock ArticleBriefMapper briefMapper;
    @Mock SettingService settingService;
    @Mock DeepResearchService self;

    private DeepResearchService service() throws Exception {
        DeepProperties props = new DeepProperties();
        props.setSearchWebEnabled(true);
        props.setMaxAgents(4);
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        DeepResearchService svc = new DeepResearchService(briefMapper, null, null, new ObjectMapper(),
                props, null, null, null, null, settingService);
        // 自注入代理在单测中不可用,注入 mock 以便断言「已调度后台执行」
        ReflectionTestUtils.setField(svc, "self", self);
        return svc;
    }

    private static ArticleBriefEntity brief(long id, Long projectId) {
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setId(id);
        b.setProjectId(projectId);
        b.setGenMode("DEEP");
        b.setPlanStatus("READY");
        b.setClarifyAnswers("[{\"q\":\"竞品\",\"a\":\"Model Y\"}]");
        b.setResearchPlan("{\"keyQuestions\":[\"价格?\",\"续航?\"]}");
        return b;
    }

    @Test
    void 重复run_第二次409且不重复调度后台() throws Exception {
        when(briefMapper.selectById(9L)).thenReturn(brief(9L, 3L));
        DeepResearchService svc = service();

        Map<String, Object> first = svc.run(3L, 9L);
        assertEquals(2, first.get("agents"));
        assertEquals("TAVILY_FIRST", first.get("strategy"));

        // 互斥锁由后台 runAsync 释放;此处 mock runAsync 不释放,第二次必须被拒
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> svc.run(3L, 9L));
        assertTrue(ex.getMessage().contains("正在研究中"), "重复触发必须返回互斥语义");
        // 只调度一次后台执行(未重复付费调用)
        verify(self, times(1)).runAsync(any(), any());
    }

    @Test
    void 非本项目brief_400语义_不调度() throws Exception {
        when(briefMapper.selectById(9L)).thenReturn(brief(9L, 99L));
        DeepResearchService svc = service();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> svc.run(3L, 9L));
        assertEquals("brief 不属于该项目", ex.getMessage());
        verify(self, never()).runAsync(any(), any());
    }

    @Test
    void 非DEEP模式_400语义_不调度() throws Exception {
        ArticleBriefEntity b = brief(9L, 3L);
        b.setGenMode("FAST");
        when(briefMapper.selectById(9L)).thenReturn(b);
        DeepResearchService svc = service();
        assertThrows(IllegalArgumentException.class, () -> svc.run(3L, 9L));
        verify(self, never()).runAsync(any(), any());
    }

    @Test
    void 澄清答案未锁定_400语义_不调度() throws Exception {
        ArticleBriefEntity b = brief(9L, 3L);
        b.setClarifyAnswers(null);
        when(briefMapper.selectById(9L)).thenReturn(b);
        DeepResearchService svc = service();
        assertEquals("澄清答案尚未锁定",
                assertThrows(IllegalArgumentException.class, () -> svc.run(3L, 9L)).getMessage());
        verify(self, never()).runAsync(any(), any());
    }

    @Test
    void 计划生成中_409语义_不调度() throws Exception {
        ArticleBriefEntity b = brief(9L, 3L);
        b.setPlanStatus("PLANNING");
        when(briefMapper.selectById(9L)).thenReturn(b);
        DeepResearchService svc = service();
        assertThrows(IllegalStateException.class, () -> svc.run(3L, 9L));
        verify(self, never()).runAsync(any(), any());
    }

    @Test
    void 无关键问题_409语义_不调度() throws Exception {
        ArticleBriefEntity b = brief(9L, 3L);
        b.setResearchPlan("{\"keyQuestions\":[]}");
        when(briefMapper.selectById(9L)).thenReturn(b);
        DeepResearchService svc = service();
        assertThrows(IllegalStateException.class, () -> svc.run(3L, 9L));
        verify(self, never()).runAsync(any(), any());
    }

    @Test
    void brief不存在_400语义() throws Exception {
        when(briefMapper.selectById(9L)).thenReturn(null);
        DeepResearchService svc = service();
        assertEquals("brief 不存在",
                assertThrows(IllegalArgumentException.class, () -> svc.run(3L, 9L)).getMessage());
    }

    /** run() 落占位后立即返回(异步语义),并把快照一并交给后台。 */
    @Test
    void 成功启动_落PENDING占位并携带策略快照() throws Exception {
        when(briefMapper.selectById(9L)).thenReturn(brief(9L, 3L));
        DeepResearchService svc = service();
        svc.run(3L, 9L);
        verify(briefMapper).updateById(any(ArticleBriefEntity.class));   // PENDING 占位落库
        verify(self).runAsync(eq(9L), any());
    }
}
