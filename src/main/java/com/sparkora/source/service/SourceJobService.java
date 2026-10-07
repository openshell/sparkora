package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.domain.entity.SourceJobEntity;
import com.sparkora.mapper.SourceJobMapper;
import com.sparkora.mapper.SourceMapper;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 信源采集任务编排服务(10-05-source-crawl-base,仿 {@code NewsSyncJobService})。
 *
 * <p>流程:createJob(源/栏目/类型) → 落任务表(RUNNING) → 返回 jobId;@{@code @Async} runJob → 采集 → 终态。
 * <b>全局串行</b>(父任务资源红线):实际采集经 {@link #globalLock} 串行化,且调度触发前先
 * {@code markStaleRunningAsFailed()} + {@code hasFreshRunning()} 防重叠——不做每源 @Async 并发。
 */
@Slf4j
@Service
public class SourceJobService {

    /** 运行中任务超过该时长视为陈旧(JVM 中途死亡遗留)。任务表无 updated_at,用 started_at。 */
    private static final long STALE_MS = 60 * 60 * 1000L;

    /** 全局采集串行锁(所有源/所有触发路径共用;采集为分钟级低频,牺牲吞吐换稳定)。 */
    private final ReentrantLock globalLock = new ReentrantLock();

    private final SourceJobMapper jobMapper;
    private final SourceMapper sourceMapper;
    private final SourceCollectService collectService;
    private final ObjectMapper json;
    /** 自注入代理,确保 @Async 生效(createJob 内 this.runJob 不走代理)。 */
    @Autowired
    @Lazy
    private SourceJobService self;

    public SourceJobService(SourceJobMapper jobMapper, SourceMapper sourceMapper,
                            SourceCollectService collectService, ObjectMapper json) {
        this.jobMapper = jobMapper;
        this.sourceMapper = sourceMapper;
        this.collectService = collectService;
        this.json = json;
    }

    /** 创建采集任务(同步落 RUNNING,带 SecurityContext)。返回 jobId。channelId 可空=整源。 */
    @Transactional
    public Long createJob(Long sourceId, Long channelId, String jobType, String batchKey) {
        if (sourceId == null) throw new IllegalArgumentException("缺少信源 id");
        SourceEntity src = sourceMapper.selectById(sourceId);
        if (src == null) throw new IllegalArgumentException("信源不存在");
        String type = (jobType == null || jobType.isBlank()) ? "MANUAL" : jobType.trim().toUpperCase();
        if (!List.of("SCHEDULED", "MANUAL", "RETRY").contains(type)) {
            throw new IllegalArgumentException("不支持的任务类型: " + jobType);
        }
        SourceJobEntity job = new SourceJobEntity();
        job.setSourceId(sourceId);
        job.setChannelId(channelId);
        job.setJobType(type);
        job.setStatus("RUNNING");
        job.setBatchKey(batchKey);
        job.setTotal(0);
        job.setSuccess(0);
        job.setFailed(0);
        job.setDegraded(0);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        (self == null ? this : self).runJob(job.getId());
        return job.getId();
    }

    /** 异步执行任务:全局串行锁内采集,统计成功/失败/降级,写失败明细与终态。 */
    @Async
    public void runJob(Long jobId) {
        SourceJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;
        // 并发锁:仅 RUNNING 可进入;若已被并发置为其他态则拒绝
        int locked = jobMapper.update(null, new UpdateWrapper<SourceJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING").set("status", "RUNNING"));
        if (locked == 0) {
            log.warn("信源采集任务 {} 已被并发处理,跳过", jobId);
            return;
        }
        globalLock.lock();
        try {
            SourceCollectService.CollectOutcome outcome =
                    collectService.collect(job.getSourceId(), job.getChannelId());
            finish(job, outcome);
        } catch (Exception e) {
            log.error("信源采集任务执行失败 jobId={}: {}", jobId, e.getMessage(), e);
            job.setErrorMsg(e.getMessage() == null ? "未知错误" : e.getMessage());
            job.setStatus("FAILED");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        } finally {
            globalLock.unlock();
        }
    }

    /** 任务收尾:置终态(SUCCESS/PARTIAL/FAILED)并写失败明细;发布窗口批次成功则回写源完成标记。 */
    @Transactional
    protected void finish(SourceJobEntity job, SourceCollectService.CollectOutcome out) {
        job.setSuccess(out.success());
        job.setFailed(out.failed());
        job.setDegraded(out.degraded());
        job.setTotal(out.success() + out.failed() + out.degraded());
        job.setFinishedAt(LocalDateTime.now());
        job.setFailedItems(toJson(out.failedItems()));
        if (out.failed() == 0 && out.degraded() == 0) {
            job.setStatus("SUCCESS");
        } else if (out.success() > 0) {
            job.setStatus("PARTIAL");
        } else {
            job.setStatus("FAILED");
        }
        jobMapper.updateById(job);
        // 发布窗口:本批全部成功(无失败无降级)才标记完成,窗口内剩余日跳过;否则次日重试
        if (job.getBatchKey() != null && !job.getBatchKey().isBlank() && "SUCCESS".equals(job.getStatus())) {
            sourceMapper.update(null, new UpdateWrapper<SourceEntity>()
                    .eq("id", job.getSourceId())
                    .set("last_batch_key", job.getBatchKey())
                    .set("updated_at", LocalDateTime.now()));
        }
    }

    /** 查询任务。 */
    public SourceJobEntity get(Long id) {
        return jobMapper.selectById(id);
    }

    /** 任务历史列表(可按 sourceId 过滤,按 id 倒序)。 */
    public List<SourceJobEntity> list(Long sourceId) {
        QueryWrapper<SourceJobEntity> qw = new QueryWrapper<>();
        if (sourceId != null) qw.eq("source_id", sourceId);
        qw.orderByDesc("id");
        return jobMapper.selectList(qw);
    }

    /** 是否存在「未过期」的运行中任务(全局防重叠用):排除 started_at 超 60 分钟的陈旧 RUNNING。 */
    public boolean hasFreshRunning() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_MS));
        return jobMapper.selectCount(new QueryWrapper<SourceJobEntity>()
                .eq("status", "RUNNING").ge("started_at", cutoff)) > 0;
    }

    /** 指定源是否存在「未过期」的运行中任务(同源运行中跳过)。 */
    public boolean hasFreshRunning(Long sourceId) {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_MS));
        return jobMapper.selectCount(new QueryWrapper<SourceJobEntity>()
                .eq("source_id", sourceId).eq("status", "RUNNING").ge("started_at", cutoff)) > 0;
    }

    /** 将陈旧的运行中任务(started_at 超 60 分钟)原子置为 FAILED,清理进程死亡残留。 */
    public int markStaleRunningAsFailed() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_MS));
        int updated = jobMapper.update(null, new UpdateWrapper<SourceJobEntity>()
                .eq("status", "RUNNING").lt("started_at", cutoff)
                .set("status", "FAILED")
                .set("finished_at", LocalDateTime.now())
                .set("error_msg", "运行超时判定为陈旧,自动终止"));
        if (updated > 0) log.warn("清理陈旧运行中信源采集任务 {} 条(超 {} 分钟)", updated, STALE_MS / 60000);
        return updated;
    }

    /** 重试失败项:从任务失败明细取 externalId 列表,创建 RETRY 任务(整源重采,幂等)。 */
    public Long retry(Long jobId) {
        SourceJobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在");
        List<String> ids = parseFailedExternalIds(job.getFailedItems());
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("该任务没有可重试的失败项");
        }
        return createJob(job.getSourceId(), job.getChannelId(), "RETRY", null);
    }

    private List<String> parseFailedExternalIds(String failedItems) {
        if (failedItems == null || failedItems.isBlank()) return List.of();
        try {
            List<Map<String, Object>> items = json.readValue(failedItems,
                    json.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<String> out = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Object g = it.get("externalId");
                if (g != null) out.add(String.valueOf(g));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
