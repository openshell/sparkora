package com.sparkora.news.service;

import com.sparkora.config.NewsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kb-cleanup AC3 新闻 Scheduler 陈旧自愈编排单测(Mockito,不连库),与车型侧对称。
 */
@ExtendWith(MockitoExtension.class)
class NewsSyncSchedulerStaleTest {

    @Mock NewsProperties props;
    @Mock NewsSyncJobService jobService;

    NewsSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NewsSyncScheduler(props, jobService);
    }

    @Test
    void 陈旧已清理_不被跳过且先清后判() {
        when(props.isSyncEnabled()).thenReturn(true);
        when(jobService.createJob(eq("SCHEDULED"))).thenReturn(8L);
        when(jobService.hasFreshRunning()).thenReturn(false);

        scheduler.scheduledSync();

        InOrder order = inOrder(jobService);
        order.verify(jobService).markStaleRunningAsFailed();
        order.verify(jobService).hasFreshRunning();
        verify(jobService).createJob(eq("SCHEDULED"));
    }

    @Test
    void 未过期RUNNING_仍阻塞不创建任务() {
        when(props.isSyncEnabled()).thenReturn(true);
        when(jobService.hasFreshRunning()).thenReturn(true);

        scheduler.scheduledSync();

        verify(jobService).markStaleRunningAsFailed();
        verify(jobService, never()).createJob(eq("SCHEDULED"));
    }

    @Test
    void 定时开关关闭_不触碰任务服务() {
        when(props.isSyncEnabled()).thenReturn(false);

        scheduler.scheduledSync();

        verify(jobService, never()).markStaleRunningAsFailed();
        verify(jobService, never()).hasFreshRunning();
    }
}
