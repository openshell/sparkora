package com.sparkora.car.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CarSyncScheduler 每分钟 tick(10-15-car-image-backfill R3/C2)单测:
 * 运行时设置驱动(开关 false 早退)、首轮仅装载 cron 不开跑、到点创建 INCREMENTAL 任务、
 * 未过期 RUNNING 阻塞、cron 未配置/非法不崩。now 由包级 tick(LocalDateTime) 注入,免真实等待。
 */
@ExtendWith(MockitoExtension.class)
class CarSyncSchedulerTickTest {

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

    @Test
    void 开关关闭_早退_不触碰任务与车型服务() {
        when(settingService.get()).thenReturn(setting(false, "0 0 3 * * ?"));

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 即使跨分钟也保持早退

        verifyNoInteractions(jobService, modelService);
    }

    @Test
    void 首轮仅装载cron_未到点_不建任务() {
        when(settingService.get()).thenReturn(setting(true, "0 0 3 * * ?"));   // 每日 03:00

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));   // 装载:nextRunAt = 次日 03:00
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 35));  // 未到点

        verifyNoInteractions(jobService, modelService);
    }

    @Test
    void 到点_按官网目录创建INCREMENTAL任务() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));   // 每分钟
        when(modelService.catalog()).thenReturn(List.of(Map.of("id", "156"), Map.of("id", "10051")));
        when(jobService.createJob(anyList(), eq("INCREMENTAL"))).thenReturn(7L);

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));   // 装载:nextRunAt = 10:01:00
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 到点

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService).hasFreshRunning();
        verify(jobService).createJob(eq(List.of("156", "10051")), eq("INCREMENTAL"));
    }

    @Test
    void 未过期RUNNING_跳过且不建任务() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));
        when(jobService.hasFreshRunning()).thenReturn(true);

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));   // 装载
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 到点

        verify(jobService).markStaleRunningAsFailed();   // 仍先清陈旧
        verify(jobService, never()).createJob(anyList(), anyString());
        verifyNoInteractions(modelService);
    }

    @Test
    void 官网目录为空_不建任务() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));
        when(modelService.catalog()).thenReturn(List.of());

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));

        verify(jobService, never()).createJob(anyList(), anyString());
    }

    @Test
    void cron未配置_本轮跳过不崩() {
        when(settingService.get()).thenReturn(setting(true, "   "));

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));

        verifyNoInteractions(jobService, modelService);
    }

    @Test
    void cron非法_本轮跳过_不建任务不崩() {
        when(settingService.get()).thenReturn(setting(true, "bad cron"));

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 非法后下一拍仍安全空转

        verifyNoInteractions(modelService);
        verify(jobService, never()).createJob(anyList(), anyString());
    }

    @Test
    void cron变化_重新装载_按新cron推算到点() {
        when(settingService.get())
                .thenReturn(setting(true, "0 0 3 * * ?"))      // 先装载:每日 03:00
                .thenReturn(setting(true, "0 * * * * ?"));     // 设置改为每分钟
        when(modelService.catalog()).thenReturn(List.of(Map.of("id", "156")));
        when(jobService.createJob(anyList(), eq("INCREMENTAL"))).thenReturn(7L);

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));  // 装载 cron A(next=次日03:00)
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));  // cron 已变 → 重新装载(next=10:02:00),不开跑
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 2, 5));  // 新 cron 到点 → 执行

        verify(jobService).createJob(eq(List.of("156")), eq("INCREMENTAL"));
    }

    @Test
    void 顺延_同一到点拍内不重复触发() {
        when(settingService.get()).thenReturn(setting(true, "0 * * * * ?"));
        when(modelService.catalog()).thenReturn(List.of(Map.of("id", "156")));
        when(jobService.createJob(anyList(), eq("INCREMENTAL"))).thenReturn(7L);

        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 0, 5));
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 5));   // 到点执行(顺延下一拍)
        scheduler.tick(LocalDateTime.of(2026, 10, 15, 10, 1, 30));  // 下一拍前:不再触发

        verify(jobService, org.mockito.Mockito.times(1)).createJob(anyList(), eq("INCREMENTAL"));
        assertTrue(true);
    }
}