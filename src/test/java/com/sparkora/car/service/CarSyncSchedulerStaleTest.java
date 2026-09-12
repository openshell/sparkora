package com.sparkora.car.service;

import com.sparkora.config.CarProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kb-cleanup AC3 车型 Scheduler 陈旧自愈编排单测(Mockito,不连库)。
 * 断言:先清陈旧再判「未过期 RUNNING」;陈旧清理后不再被跳过(继续 createJob);
 * 未过期 RUNNING 仍阻塞(防重叠不回归)。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncSchedulerStaleTest {

    @Mock CarProperties props;
    @Mock CarSyncJobService jobService;
    @Mock CarModelService modelService;

    CarSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CarSyncScheduler(props, jobService, modelService);
    }

    @Test
    void 陈旧已清理_不被跳过且先清后判() {
        when(props.isSyncEnabled()).thenReturn(true);
        when(modelService.catalog()).thenReturn(List.of(Map.of("id", "1001")));
        when(jobService.createJob(anyList(), eq("SCHEDULED"))).thenReturn(7L);
        // 陈旧清理无碍本轮;清理后无未过期 RUNNING
        when(jobService.hasFreshRunning()).thenReturn(false);

        scheduler.scheduledSync();

        InOrder order = inOrder(jobService);
        order.verify(jobService).markStaleRunningAsFailed();
        order.verify(jobService).hasFreshRunning();
        verify(jobService).createJob(anyList(), eq("SCHEDULED"));
    }

    @Test
    void 未过期RUNNING_仍阻塞不创建任务() {
        when(props.isSyncEnabled()).thenReturn(true);
        when(jobService.hasFreshRunning()).thenReturn(true);

        scheduler.scheduledSync();

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService, never()).createJob(anyList(), eq("SCHEDULED"));
    }

    @Test
    void 定时开关关闭_不触碰任务服务() {
        when(props.isSyncEnabled()).thenReturn(false);

        scheduler.scheduledSync();

        verify(jobService, never()).markStaleRunningAsFailed();
        verify(jobService, never()).hasFreshRunning();
    }
}
