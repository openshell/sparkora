package com.sparkora.car.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 车型 Scheduler 防重叠/陈旧自愈编排单测(Mockito,不连库;10-15-car-image-backfill R3 后
 * 由运行时设置驱动,构造器改注入 SettingService,原 CarProperties 开关断言移除/改由设置承载)。
 * 断言:到点时先清陈旧再判「未过期 RUNNING」;陈旧清理后不再被跳过(继续 createJob);
 * 未过期 RUNNING 仍阻塞(防重叠不回归);开关关闭(运行时设置 car_sync_enabled=false)不触碰任务服务。
 * now 经包级 tick(LocalDateTime) 注入,首轮装载 cron 后第二拍到点执行。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncSchedulerStaleTest {

    @Mock SettingService settingService;
    @Mock CarSyncJobService jobService;
    @Mock CarModelService modelService;

    CarSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CarSyncScheduler(settingService, jobService, modelService);
    }

    private static SettingEntity setting(Boolean enabled, String cron) {
        SettingEntity s = new SettingEntity();
        s.setId(1L);
        s.setCarSyncEnabled(enabled);
        s.setCarSyncCron(cron);
        return s;
    }

    private void loadAndFire() {
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));   // 首轮:装载 cron(每分钟)
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 到点执行
    }

    @Test
    void 陈旧已清理_不被跳过且先清后判() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));
        when(modelService.catalog()).thenReturn(List.of(Map.of("id", "1001")));
        when(jobService.createJob(anyList(), eq("INCREMENTAL"))).thenReturn(7L);
        // 陈旧清理无碍本轮;清理后无未过期 RUNNING
        when(jobService.hasFreshRunning()).thenReturn(false);

        loadAndFire();

        InOrder order = inOrder(jobService);
        order.verify(jobService).markStaleRunningAsFailed();
        order.verify(jobService).hasFreshRunning();
        verify(jobService).createJob(anyList(), eq("INCREMENTAL"));
    }

    @Test
    void 未过期RUNNING_仍阻塞不创建任务() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));
        when(jobService.hasFreshRunning()).thenReturn(true);

        loadAndFire();

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService, never()).createJob(anyList(), eq("INCREMENTAL"));
        verifyNoInteractions(modelService);
    }

    @Test
    void 运行时开关关闭_不触碰任务服务() {
        when(settingService.get()).thenReturn(setting(false, "0 * * * * ?"));

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));

        verifyNoInteractions(jobService);
        verifyNoInteractions(modelService);
    }
}
