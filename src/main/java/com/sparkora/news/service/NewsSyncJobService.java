package com.sparkora.news.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.NewsSyncJobEntity;
import com.sparkora.mapper.NewsSyncJobMapper;
import com.sparkora.security.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新闻同步任务编排服务(C2,仿 CarSyncJobService)。
 *
 * 流程:createJob(jobType) → 落任务表(RUNNING) → 返回 jobId
 *      @Async runJob(jobId) → FULL/INCREMENT 同步 → 更新进度/失败明细 → 终态
 *
 * 并发控制:任务启动时原子更新 status(RUNNING→RUNNING 影响行数=0 则拒绝),数据库行锁避免重复触发。
 * 注意:@Async 线程无 SecurityContext,created_by 在同步的 createJob 阶段写入。
 */
@Slf4j
@Service
public class NewsSyncJobService {

    private final NewsSyncJobMapper jobMapper;
    private final NewsService newsService;
    private final ObjectMapper json;
    // 自注入代理,确保 @Async 生效(createJob 内 this.runJob 不会走代理)
    @Autowired
    @Lazy
    private NewsSyncJobService self;

    public NewsSyncJobService(NewsSyncJobMapper jobMapper, NewsService newsService, ObjectMapper json) {
        this.jobMapper = jobMapper;
        this.newsService = newsService;
        this.json = json;
    }

    /** 创建同步任务(同步调用,带 SecurityContext)。返回 jobId。 */
    @Transactional
    public Long createJob(String jobType) {
        return createJob(jobType, null);
    }

    /** 创建同步任务(可带重试目标 id 列表)。返回 jobId。 */
    @Transactional
    public Long createJob(String jobType, List<String> retryIds) {
        String type = (jobType == null || jobType.isBlank()) ? "INCREMENT" : jobType.trim().toUpperCase();
        if (!List.of("FULL", "INCREMENT", "RETRY", "SCHEDULED").contains(type)) {
            throw new IllegalArgumentException("不支持的任务类型: " + jobType);
        }
        NewsSyncJobEntity job = new NewsSyncJobEntity();
        job.setJobType(type);
        job.setStatus("RUNNING");
        job.setTotal(retryIds == null ? 0 : retryIds.size());
        job.setSuccess(0);
        job.setFailed(0);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        self.runJob(job.getId(), retryIds);
        return job.getId();
    }

    /** 异步执行任务:按 jobType 全量/增量/重试同步,统计成功/失败,写失败明细。 */
    @Async
    public void runJob(Long jobId, List<String> retryIds) {
        NewsSyncJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;
        // 并发锁:仅 RUNNING 可进入;若已被并发置为其他态则拒绝
        int locked = jobMapper.update(null, new UpdateWrapper<NewsSyncJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING").set("status", "RUNNING"));
        if (locked == 0) {
            log.warn("新闻同步任务 {} 已被并发处理,跳过", jobId);
            return;
        }
        final Long jobIdRef = jobId;
        java.util.function.BiConsumer<Integer, Integer> onProgress = (success, failed) -> {
            // 每完成一条更新一次进度(前端轮询可见)
            job.setSuccess(success);
            job.setFailed(failed);
            jobMapper.updateById(job);
        };
        NewsService.SyncOutcome outcome;
        try {
            if ("RETRY".equals(job.getJobType())) {
                outcome = newsService.retryFailed(retryIds);
            } else if ("FULL".equals(job.getJobType())) {
                outcome = newsService.sync(false, onProgress);
            } else {
                outcome = newsService.sync(true, onProgress);
            }
        } catch (Exception e) {
            log.error("新闻同步任务执行失败 jobId={}: {}", jobIdRef, e.getMessage(), e);
            job.setErrorMsg(e.getMessage() == null ? "未知错误" : e.getMessage());
            job.setStatus("FAILED");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            return;
        }
        finish(job, outcome.success(), outcome.failed(), outcome.failedItems());
    }

    /** 任务收尾:置终态(SUCCESS/PARTIAL/FAILED)并写失败明细。 */
    @Transactional
    protected void finish(NewsSyncJobEntity job, int success, int failed, List<Map<String, Object>> failedItems) {
        job.setSuccess(success);
        job.setFailed(failed);
        job.setTotal(success + failed);
        job.setFinishedAt(LocalDateTime.now());
        job.setFailedItems(toJson(failedItems));
        if (failed == 0) {
            job.setStatus("SUCCESS");
        } else if (success > 0) {
            job.setStatus("PARTIAL");
        } else {
            job.setStatus("FAILED");
        }
        jobMapper.updateById(job);
    }

    /** 查询任务。 */
    public NewsSyncJobEntity get(Long id) {
        return jobMapper.selectById(id);
    }

    /** 最近任务列表(按创建时间倒序)。 */
    public List<NewsSyncJobEntity> list() {
        return jobMapper.selectList(new QueryWrapper<NewsSyncJobEntity>().orderByDesc("id"));
    }

    /** 运行中任务超过该时长视为陈旧(JVM 中途死亡遗留),定时任务不再被其阻塞。任务表无 updated_at,用 started_at。 */
    private static final long SYNC_STALE_MS = 60 * 60 * 1000L;

    /** 是否存在「未过期」的运行中任务(定时任务防重叠用):排除 started_at 超 60 分钟的陈旧 RUNNING。 */
    public boolean hasFreshRunning() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        return jobMapper.selectCount(new QueryWrapper<NewsSyncJobEntity>()
                .eq("status", "RUNNING").ge("started_at", cutoff)) > 0;
    }

    /** 将陈旧的运行中任务(started_at 超 60 分钟)原子置为 FAILED,清理进程死亡残留、解除定时任务永久阻塞。 */
    public int markStaleRunningAsFailed() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        int updated = jobMapper.update(null, new UpdateWrapper<NewsSyncJobEntity>()
                .eq("status", "RUNNING").lt("started_at", cutoff)
                .set("status", "FAILED")
                .set("finished_at", LocalDateTime.now())
                .set("error_msg", "运行超时判定为陈旧,自动终止"));
        if (updated > 0) log.warn("清理陈旧运行中新闻同步任务 {} 条(超 {} 分钟)", updated, SYNC_STALE_MS / 60000);
        return updated;
    }

    /** 重试失败项:从任务失败明细取 newsId 列表,创建 RETRY 任务(逐条重抓)。 */
    public Long retry(Long jobId) {
        NewsSyncJobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在");
        List<String> newsIds = parseFailedNewsIds(job.getFailedItems());
        if (newsIds.isEmpty()) {
            throw new IllegalArgumentException("该任务没有可重试的失败新闻");
        }
        return createJob("RETRY", newsIds);
    }

    private List<String> parseFailedNewsIds(String failedItems) {
        if (failedItems == null || failedItems.isBlank()) return List.of();
        try {
            List<Map<String, Object>> items = json.readValue(failedItems,
                    json.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<String> out = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Object g = it.get("newsId");
                if (g != null) out.add(String.valueOf(g));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }
}
