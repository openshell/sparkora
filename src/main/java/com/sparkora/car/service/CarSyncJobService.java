package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.dto.CleanStats;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarSyncJobEntity;
import com.sparkora.mapper.CarModelMapper;
import com.sparkora.mapper.CarSyncJobMapper;
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
 * 车型同步任务编排服务(S6 重构:异步任务化;10-15-car-image-backfill B 扩两类任务)。
 *
 * job_type 四类:
 *   SELECTED / RETRY        —— 全量 syncOne(既有语义零变化)
 *   IMAGE_BACKFILL          —— 轻回填:仅拉 goodsInfo → persistImages 图片域(封面+详情图集) + 指纹收敛,不触参数/清洗/向量
 *   INCREMENTAL             —— 增量分派:按 sync_fingerprint 决定 跳过/轻回填/全量(dispatchIncremental)
 *   (SCHEDULED 为旧定时类型历史值,新调度改创建 INCREMENTAL)
 *
 * 取消全量同步,仅手动指定车型同步。流程:
 *   createJob(goodsIds) → 落任务表(RUNNING) → 返回 jobId
 *   @Async runJob(jobId) → 逐车型按类型分派 → 更新任务进度/明细
 *
 * 并发控制:任务启动时原子更新 status(RUNNING→RUNNING 影响行数=0 则拒绝),
 * 用数据库行锁避免重复触发,无需 Redis。
 *
 * 明细结构:失败条目 {goodsId,name,error};备注条目 {goodsId,name,note(如「官网无图」「跳过:指纹一致」)}。
 * 注意:@Async 线程无 SecurityContext,created_by 在同步的 createJob 阶段写入。
 */
@Slf4j
@Service
public class CarSyncJobService {

    private final CarSyncJobMapper jobMapper;
    private final CarModelMapper modelMapper;
    private final CarModelService modelService;
    private final ObjectMapper json;
    // 自注入代理,确保 @Async 生效(createJob 内 this.runJob 不会走代理)
    @Autowired
    @Lazy
    private CarSyncJobService self;

    public CarSyncJobService(CarSyncJobMapper jobMapper, CarModelMapper modelMapper,
                             CarModelService modelService, ObjectMapper json) {
        this.jobMapper = jobMapper;
        this.modelMapper = modelMapper;
        this.modelService = modelService;
        this.json = json;
    }

    /** 创建同步任务(同步调用,带 SecurityContext)。返回 jobId。jobType: SELECTED/RETRY(既有语义零变化)。 */
    @Transactional
    public Long createJob(List<String> goodsIds, String jobType) {
        if (goodsIds == null || goodsIds.isEmpty()) throw new IllegalArgumentException("请选择要同步的车型");
        return insertJobAndRun(goodsIds, jobType == null ? "SELECTED" : jobType);
    }

    /**
     * 创建「图片补入库」任务(R1 存量回填):覆盖全部库内存活车型(orderByAsc id),
     * job_type=IMAGE_BACKFILL;逐车型轻回填(仅图片域 + 指纹收敛),不触参数/清洗/向量。
     */
    @Transactional
    public Long createBackfillJob() {
        List<CarModelEntity> rows = modelMapper.selectList(new QueryWrapper<CarModelEntity>()
                .select("id", "goods_id").orderByAsc("id"));
        List<String> goodsIds = new ArrayList<>();
        for (CarModelEntity m : rows) {
            if (m.getGoodsId() != null && !m.getGoodsId().isBlank()) goodsIds.add(m.getGoodsId());
        }
        if (goodsIds.isEmpty()) throw new IllegalArgumentException("库内没有可回填的车型");
        return insertJobAndRun(goodsIds, "IMAGE_BACKFILL");
    }

    /** 落任务行并触发异步执行(自注入代理触发 @Async;单测 self 为空退化为同步直调)。 */
    private Long insertJobAndRun(List<String> goodsIds, String jobType) {
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setJobType(jobType);
        job.setStatus("RUNNING");
        job.setTotal(goodsIds.size());
        job.setSuccess(0);
        job.setFailed(0);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        (self == null ? this : self).runJob(job.getId(), goodsIds);
        return job.getId();
    }

    /** 异步执行任务:按 job_type 分派(全量/轻回填/增量),统计成功/失败,写明细。 */
    @Async
    public void runJob(Long jobId, List<String> goodsIds) {
        CarSyncJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;
        // 并发锁:仅 RUNNING 可进入;若已被并发置为其他态则拒绝
        int locked = jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<CarSyncJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING").set("status", "RUNNING"));
        if (locked == 0) {
            log.warn("同步任务 {} 已被并发处理,跳过", jobId);
            return;
        }
        String type = job.getJobType() == null ? "SELECTED" : job.getJobType();
        // 明细:失败条目 {goodsId,name,error} + 备注条目 {goodsId,name,note}(官网无图/跳过:指纹一致)
        List<Map<String, Object>> detailItems = new ArrayList<>();
        int success = 0;
        int failed = 0;
        // 清洗方式聚合(可观测:RULE/AI/FALLBACK 汇总,随任务收尾日志输出);轻回填/跳过不触清洗域,清洗净为 null
        int aggRule = 0, aggAi = 0, aggFallback = 0;
        for (String goodsId : goodsIds) {
            try {
                if ("IMAGE_BACKFILL".equals(type)) {
                    // 轻回填:仅图片域 + 指纹收敛;版本/参数/清洗/向量域零触碰
                    CarModelService.IncrementalOutcome o = modelService.backfillImages(goodsId);
                    appendNote(detailItems, goodsId, o.note());
                    String name = o.outcome() != null && o.outcome().model() != null
                            ? o.outcome().model().getName() : goodsId;
                    log.info("车型图片回填完成 goodsId={} name={} note={}",
                            goodsId, name, o.note() == null ? "-" : o.note());
                    success++;
                } else if ("INCREMENTAL".equals(type)) {
                    // 增量分派:跳过/轻回填/全量由指纹决定;note 透传任务明细(前端原样展示)
                    CarModelService.IncrementalOutcome o = modelService.dispatchIncremental(goodsId);
                    appendNote(detailItems, goodsId, o.note());
                    if (o.outcome() == null) {
                        log.info("车型增量跳过 goodsId={} note={}", goodsId, o.note());
                    } else {
                        CleanStats st = o.outcome().cleanStats();
                        if (st != null) {
                            aggRule += st.getRule();
                            aggAi += st.getAi();
                            aggFallback += st.getFallback();
                        }
                        log.info("车型增量同步完成 goodsId={} note={} 清洗统计 RULE={} AI={} FALLBACK={}",
                                goodsId, o.note() == null ? "-" : o.note(),
                                st == null ? 0 : st.getRule(), st == null ? 0 : st.getAi(), st == null ? 0 : st.getFallback());
                    }
                    success++;
                } else {
                    // 全量路径(SELECTED/RETRY),语义零变化
                    markSyncing(goodsId);
                    CarModelService.SyncOutcome outcome = modelService.syncOne(goodsId);
                    CleanStats st = outcome.cleanStats();
                    aggRule += st.getRule();
                    aggAi += st.getAi();
                    aggFallback += st.getFallback();
                    log.info("车型同步完成 goodsId={} name={} 清洗统计 RULE={} AI={} FALLBACK={}",
                            goodsId, outcome.model().getName(), st.getRule(), st.getAi(), st.getFallback());
                    success++;
                }
            } catch (Exception e) {
                failed++;
                String err = e.getMessage() == null ? "未知错误" : e.getMessage();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("goodsId", goodsId);
                item.put("name", modelName(goodsId));
                item.put("error", err);
                detailItems.add(item);
                log.warn("车型同步失败 goodsId={}: {}", goodsId, err);
                // R2 失败状态可信化:回写车型 FAILED + 失败原因,避免永久停留 SYNCING;
                // 回写失败仅告警,不覆盖任务原始异常/终态(SELECTED/IMAGE_BACKFILL/INCREMENTAL 同口径)
                try {
                    modelService.markSyncFailed(goodsId, err);
                } catch (Exception ex) {
                    log.warn("车型失败状态回写失败(忽略) goodsId={}: {}", goodsId, ex.getMessage());
                }
            }
            // 每完成一个更新一次进度(前端轮询可见)
            job.setSuccess(success);
            job.setFailed(failed);
            jobMapper.updateById(job);
        }
        log.info("同步任务完成 jobId={} type={} 清洗聚合 total={} RULE={} AI={} FALLBACK={} (fallbackPct={}%)",
                jobId, type, aggRule + aggAi + aggFallback, aggRule, aggAi, aggFallback,
                (aggRule + aggAi + aggFallback) == 0 ? 0
                        : aggFallback * 100 / (aggRule + aggAi + aggFallback));
        finish(job, success, failed, detailItems);
    }

    /** 追加「成功但有可汇报信息」的明细条目(note 键,无 error;不参与 retry 重试候选)。 */
    private void appendNote(List<Map<String, Object>> detailItems, String goodsId, String note) {
        if (note == null || note.isBlank()) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("goodsId", goodsId);
        item.put("name", modelName(goodsId));
        item.put("note", note);
        detailItems.add(item);
    }

    /** 任务收尾:置终态(SUCCESS/PARTIAL/FAILED)并写明细(失败+备注)。 */
    @Transactional
    protected void finish(CarSyncJobEntity job, int success, int failed, List<Map<String, Object>> detailItems) {
        job.setSuccess(success);
        job.setFailed(failed);
        job.setFinishedAt(LocalDateTime.now());
        job.setFailedItems(toJson(detailItems));
        if (failed == 0) {
            job.setStatus("SUCCESS");
        } else if (success > 0) {
            job.setStatus("PARTIAL");
        } else {
            job.setStatus("FAILED");
        }
        jobMapper.updateById(job);
    }

    /** 查询任务(含失败明细解析)。 */
    public CarSyncJobEntity get(Long id) {
        return jobMapper.selectById(id);
    }

    /** 最近任务列表(按创建时间倒序)。 */
    public List<CarSyncJobEntity> list() {
        return jobMapper.selectList(new QueryWrapper<CarSyncJobEntity>().orderByDesc("id"));
    }

    /** 运行中任务超过该时长视为陈旧(JVM 中途死亡遗留),定时任务不再被其阻塞。任务表无 updated_at,用 started_at。 */
    private static final long SYNC_STALE_MS = 60 * 60 * 1000L;

    /** 是否存在「未过期」的运行中任务(定时任务防重叠用):排除 started_at 超 60 分钟的陈旧 RUNNING。 */
    public boolean hasFreshRunning() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        return jobMapper.selectCount(new QueryWrapper<CarSyncJobEntity>()
                .eq("status", "RUNNING").ge("started_at", cutoff)) > 0;
    }

    /** 将陈旧的运行中任务(started_at 超 60 分钟)原子置为 FAILED,清理进程死亡残留、解除定时任务永久阻塞。 */
    public int markStaleRunningAsFailed() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        int updated = jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<CarSyncJobEntity>()
                .eq("status", "RUNNING").lt("started_at", cutoff)
                .set("status", "FAILED")
                .set("finished_at", LocalDateTime.now())
                .set("error_msg", "运行超时判定为陈旧,自动终止"));
        if (updated > 0) log.warn("清理陈旧运行中同步任务 {} 条(超 {} 分钟)", updated, SYNC_STALE_MS / 60000);
        return updated;
    }

    /** 重试失败项:从任务失败明细取 goodsId 列表,创建 RETRY 任务。 */
    public Long retry(Long jobId) {
        CarSyncJobEntity job = jobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在");
        List<String> goodsIds = parseFailedGoodsIds(job.getFailedItems());
        if (goodsIds.isEmpty()) throw new IllegalArgumentException("该任务没有可重试的失败车型");
        return createJob(goodsIds, "RETRY");
    }

    private void markSyncing(String goodsId) {
        CarModelEntity m = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        if (m == null) return;
        m.setSyncStatus("SYNCING");
        m.setLastSyncError(null);   // 新一次同步开始即清空历史失败原因(R2)
        m.setUpdatedAt(LocalDateTime.now());
        modelMapper.updateById(m);
    }

    private String modelName(String goodsId) {
        CarModelEntity m = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        return m == null ? goodsId : m.getName();
    }

    private List<String> parseFailedGoodsIds(String failedItems) {
        if (failedItems == null || failedItems.isBlank()) return List.of();
        try {
            List<Map<String, Object>> items = json.readValue(failedItems,
                    json.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<String> out = new ArrayList<>();
            for (Map<String, Object> it : items) {
                // 备注条目(如「官网无图」「跳过:指纹一致」)非失败项,不参与重试候选
                if (it.get("error") == null) continue;
                Object g = it.get("goodsId");
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
