package com.sparkora.news.service;

import com.sparkora.config.NewsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新闻定时增量同步(C2,仿 CarSyncScheduler)。
 *
 * 默认关闭(sync-enabled=false),需在 .env 显式开启;cron 默认每天 03:30(错开车型同步)。
 * 运行中任务存在时跳过本轮,避免与手动任务重叠;全程 try/catch 不抛出(定时线程静默失败不可见)。
 */
@Slf4j
@Component
public class NewsSyncScheduler {

    private final NewsProperties props;
    private final NewsSyncJobService jobService;

    public NewsSyncScheduler(NewsProperties props, NewsSyncJobService jobService) {
        this.props = props;
        this.jobService = jobService;
    }

    @Scheduled(cron = "${sparkora.news.sync-cron:0 30 3 * * ?}")
    public void scheduledSync() {
        if (!props.isSyncEnabled()) return;
        try {
            // 先清理进程死亡遗留的陈旧 RUNNING(超 60 分钟),否则定时增量会被永久阻塞
            jobService.markStaleRunningAsFailed();
            if (jobService.hasFreshRunning()) {
                log.warn("定时新闻同步跳过:已有运行中的同步任务");
                return;
            }
            Long jobId = jobService.createJob("SCHEDULED");
            log.info("定时新闻增量同步已创建任务 jobId={}", jobId);
        } catch (Exception e) {
            log.error("定时新闻同步失败: {}", e.getMessage(), e);
        }
    }
}
