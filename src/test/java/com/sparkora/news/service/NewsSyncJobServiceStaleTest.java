package com.sparkora.news.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.NewsSyncJobEntity;
import com.sparkora.mapper.NewsSyncJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2/kb-cleanup 新闻同步任务「陈旧自愈」单测(Mockito,不连库),与车辆侧对称。
 */
@ExtendWith(MockitoExtension.class)
class NewsSyncJobServiceStaleTest {

    @Mock NewsSyncJobMapper jobMapper;
    @Mock NewsService newsService;

    NewsSyncJobService service;

    @BeforeEach
    void setUp() {
        service = new NewsSyncJobService(jobMapper, newsService, new ObjectMapper());
    }

    @Test
    void 陈旧置失败_条件含RUNNING与started_at阈值且置终态字段() {
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        int updated = service.markStaleRunningAsFailed();

        assertEquals(1, updated);
        ArgumentCaptor<Wrapper<NewsSyncJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).update(isNull(), captor.capture());
        Wrapper<NewsSyncJobEntity> w = captor.getValue();
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) w;
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains("<"), "应按 started_at 早于阈值过滤: " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"), "应过滤 RUNNING");
        String set = w.getSqlSet();
        assertTrue(set.contains("status") && set.contains("finished_at") && set.contains("error_msg"), "SET 列缺失: " + set);
        assertTrue(aw.getParamNameValuePairs().containsValue("FAILED"), "应置 FAILED");
        assertTrue(aw.getParamNameValuePairs().containsValue("运行超时判定为陈旧,自动终止"), "应写陈旧说明");
    }

    @Test
    void hasFreshRunning_条件含RUNNING与started_at不早于阈值() {
        when(jobMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        boolean fresh = service.hasFreshRunning();

        assertTrue(fresh);
        ArgumentCaptor<Wrapper<NewsSyncJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).selectCount(captor.capture());
        Wrapper<NewsSyncJobEntity> w = captor.getValue();
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) w;
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains(">="), "应只算未过期(started_at >= cutoff): " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"), "应过滤 RUNNING");
    }
}
