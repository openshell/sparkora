package com.sparkora.source.service;

import com.sparkora.config.SourceProperties;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.SourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态调度 + 发布窗口语义单测(10-05-source-crawl-base,AC-B2/AC-B8)。
 * 不启动 Spring:mock {@link TaskScheduler}/mapper/jobService,断言注册/注销与窗口判定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceScheduleServiceTest {

    @Mock SourceMapper sourceMapper;
    @Mock SourceJobService jobService;
    @Mock TaskScheduler taskScheduler;

    SourceProperties props;

    @BeforeEach
    void setUp() {
        props = new SourceProperties();
        props.setCollectEnabled(true);
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));
    }

    private SourceScheduleService newService(LocalDate today) {
        return new SourceScheduleService(sourceMapper, jobService, props, taskScheduler, () -> today);
    }

    private SourceScheduleService newService(LocalDate today, java.time.LocalDateTime now) {
        return new SourceScheduleService(sourceMapper, jobService, props, taskScheduler, () -> today, () -> now);
    }

    private static SourceEntity source(Long id, boolean enabled, String cron) {
        SourceEntity s = new SourceEntity();
        s.setId(id);
        s.setEnabled(enabled);
        s.setCron(cron);
        s.setType("SITE");
        return s;
    }

    @Test
    void 注册改cron注销_句柄取消且重注册() {
        SourceScheduleService svc = newService(LocalDate.of(2026, 10, 8));

        svc.register(source(1L, true, "0 0 3 * * ?"));
        assertTrue(svc.isRegistered(1L));

        SourceEntity changed = source(1L, true, "0 30 4 * * ?");
        svc.register(changed);
        assertTrue(svc.isRegistered(1L), "改 cron 后仍应注册");

        svc.unregister(1L);
        assertFalse(svc.isRegistered(1L));
        // 两次注册 → 至少 schedule 两次;注销后 cancel 被调用
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void 禁用源不注册() {
        SourceScheduleService svc = newService(LocalDate.of(2026, 10, 8));
        assertFalse(svc.register(source(2L, false, "0 0 3 * * ?")));
        assertFalse(svc.isRegistered(2L));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void 总开关关闭_registerAll不注册() {
        props.setCollectEnabled(false);
        SourceScheduleService svc = newService(LocalDate.of(2026, 10, 8));
        when(sourceMapper.selectList(any())).thenReturn(java.util.List.of(source(3L, true, "0 0 3 * * ?")));
        assertEquals(0, svc.registerAll());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void 发布窗口源用每日cron_无窗口用自身cron() {
        SourceEntity window = source(1L, true, "0 0 3 1 * ?");
        window.setWindowStartDay(8);
        window.setWindowEndDay(11);
        assertEquals(SourceScheduleService.DAILY_CRON, SourceScheduleService.cronFor(window));

        SourceEntity plain = source(2L, true, "0 0 3 * * ?");
        assertEquals("0 0 3 * * ?", SourceScheduleService.cronFor(plain));
    }

    @Test
    void 窗口判定_起止日含端点_起止顺序容错() {
        SourceEntity s = source(1L, false, null);
        s.setWindowStartDay(11);
        s.setWindowEndDay(8);   // 反序也应容错
        assertTrue(SourceScheduleService.inWindow(s, 8));
        assertTrue(SourceScheduleService.inWindow(s, 11));
        assertTrue(SourceScheduleService.inWindow(s, 9));
        assertFalse(SourceScheduleService.inWindow(s, 7));
        assertFalse(SourceScheduleService.inWindow(s, 12));
        assertTrue(SourceScheduleService.hasWindow(s));
    }

    @Test
    void 窗口内触发_创建带批次键的任务() {
        SourceEntity s = source(5L, true, "0 0 3 1 * ?");
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        when(sourceMapper.selectById(5L)).thenReturn(s);
        when(jobService.hasFreshRunning(5L)).thenReturn(false);
        when(jobService.createJob(eq(5L), eq(null), eq("SCHEDULED"), eq("5-2026-10"))).thenReturn(9L);

        newService(LocalDate.of(2026, 10, 8)).runScheduled(5L);

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService).createJob(eq(5L), eq(null), eq("SCHEDULED"), eq("5-2026-10"));
    }

    @Test
    void 非窗口期不触发() {
        SourceEntity s = source(5L, true, "0 0 3 1 * ?");
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        when(sourceMapper.selectById(5L)).thenReturn(s);

        newService(LocalDate.of(2026, 10, 20)).runScheduled(5L);

        verify(jobService, never()).createJob(anyLong(), any(), any(), any());
    }

    @Test
    void 本批已采完_窗口内跳过() {
        SourceEntity s = source(5L, true, "0 0 3 1 * ?");
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        s.setLastBatchKey("5-2026-10");
        when(sourceMapper.selectById(5L)).thenReturn(s);

        newService(LocalDate.of(2026, 10, 9)).runScheduled(5L);

        verify(jobService, never()).createJob(anyLong(), any(), any(), any());
    }

    @Test
    void 同源运行中_跳过本轮() {
        SourceEntity s = source(5L, true, "0 0 3 * * ?");
        when(sourceMapper.selectById(5L)).thenReturn(s);
        when(jobService.hasFreshRunning(5L)).thenReturn(true);

        newService(LocalDate.of(2026, 10, 8)).runScheduled(5L);

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService, never()).createJob(anyLong(), any(), any(), any());
    }

    @Test
    void 批次键含源与年月() {
        assertEquals("7-2026-10", SourceScheduleService.batchKey(7L, YearMonth.of(2026, 10)));
    }

    // ==================== G6 nextRunAt(10-09-cpca-gasgoo-collection,R11/AC-11) ====================

    @Test
    void nextRunAt_停用源返回null() {
        SourceEntity s = source(1L, false, "0 0 3 * * ?");
        assertEquals(null, newService(LocalDate.of(2026, 10, 8),
                java.time.LocalDateTime.of(2026, 10, 8, 1, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_总开关关闭返回null() {
        props.setCollectEnabled(false);
        SourceEntity s = source(1L, true, "0 0 3 * * ?");
        assertEquals(null, newService(LocalDate.of(2026, 10, 8),
                java.time.LocalDateTime.of(2026, 10, 8, 1, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_无窗口用cron次日() {
        SourceEntity s = source(1L, true, "0 0 3 * * ?");   // 每日 03:00
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 8, 5, 0);
        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 3, 0),
                newService(LocalDate.of(2026, 10, 8), now).nextRunAt(s));
    }

    @Test
    void nextRunAt_无窗口非法cron降级null() {
        SourceEntity s = source(1L, true, "not-a-cron");
        assertEquals(null, newService(LocalDate.of(2026, 10, 8),
                java.time.LocalDateTime.of(2026, 10, 8, 1, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_无cron返回null() {
        SourceEntity s = source(1L, true, null);
        assertEquals(null, newService(LocalDate.of(2026, 10, 8),
                java.time.LocalDateTime.of(2026, 10, 8, 1, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_窗口内且未到今日触发_返回今日0330() {
        SourceEntity s = source(5L, true, null);
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        // 10-09 在窗口内,当前 01:00 早于 03:30 → 今日 03:30
        assertEquals(java.time.LocalDateTime.of(2026, 10, 9, 3, 30),
                newService(LocalDate.of(2026, 10, 9), java.time.LocalDateTime.of(2026, 10, 9, 1, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_窗口内已过今日触发_返回次日0330() {
        SourceEntity s = source(5L, true, null);
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        // 10-09 已过 03:30 且次日 10-10 仍在窗口内 → 次日 03:30
        assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 3, 30),
                newService(LocalDate.of(2026, 10, 9), java.time.LocalDateTime.of(2026, 10, 9, 5, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_窗口内最后一日已过触发_返回下月窗口起始日() {
        SourceEntity s = source(5L, true, null);
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        // 10-11 已过 03:30,次日 10-12 出窗口 → 下月 11-08 03:30
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 3, 30),
                newService(LocalDate.of(2026, 10, 11), java.time.LocalDateTime.of(2026, 10, 11, 5, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_窗口未开始_返回本月窗口起始日() {
        SourceEntity s = source(5L, true, null);
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        // 10-05 尚未进入窗口 → 本月 10-08 03:30
        assertEquals(java.time.LocalDateTime.of(2026, 10, 8, 3, 30),
                newService(LocalDate.of(2026, 10, 5), java.time.LocalDateTime.of(2026, 10, 5, 9, 0)).nextRunAt(s));
    }

    @Test
    void nextRunAt_本批已完成_窗口内跳过返回下月起始日() {
        SourceEntity s = source(5L, true, null);
        s.setWindowStartDay(8);
        s.setWindowEndDay(11);
        s.setLastBatchKey("5-2026-10");
        // 10-09 本批已完成 → 跳过本月窗口 → 下月 11-08 03:30
        assertEquals(java.time.LocalDateTime.of(2026, 11, 8, 3, 30),
                newService(LocalDate.of(2026, 10, 9), java.time.LocalDateTime.of(2026, 10, 9, 1, 0)).nextRunAt(s));
    }
}
