package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.CarSyncJobEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarSyncJobMapper;
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
 * C1/kb-cleanup 车型同步任务「陈旧自愈」单测(Mockito,不连库)。
 * 断言 WHERE 过滤 RUNNING + started_at 早于阈值,SET 置 FAILED/finished_at/error_msg,
 * 且 hasFreshRunning 的阈值过滤条件正确(防陈旧阻塞定时任务)。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncJobServiceStaleTest {

    @Mock CarSyncJobMapper jobMapper;
    @Mock CarModelMapper modelMapper;
    @Mock CarModelService modelService;

    CarSyncJobService service;

    @BeforeEach
    void setUp() {
        service = new CarSyncJobService(jobMapper, modelMapper, modelService, new ObjectMapper());
    }

    @Test
    void 陈旧置失败_条件含RUNNING与started_at阈值且置终态字段() {
        when(jobMapper.update(isNull(), any(Wrapper.class))).thenReturn(2);

        int updated = service.markStaleRunningAsFailed();

        assertEquals(2, updated);
        ArgumentCaptor<Wrapper<CarSyncJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).update(isNull(), captor.capture());
        Wrapper<CarSyncJobEntity> w = captor.getValue();
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) w;
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains("<"), "应按 started_at 早于阈值过滤: " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"), "应过滤 RUNNING: " + aw.getParamNameValuePairs());
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
        ArgumentCaptor<Wrapper<CarSyncJobEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(jobMapper).selectCount(captor.capture());
        Wrapper<CarSyncJobEntity> w = captor.getValue();
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) w;
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("status") && sql.contains("started_at"), "条件列缺失: " + sql);
        assertTrue(sql.contains(">="), "应只算未过期(started_at >= cutoff): " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("RUNNING"), "应过滤 RUNNING");
    }
}
