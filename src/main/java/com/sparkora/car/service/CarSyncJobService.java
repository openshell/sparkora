package com.sparkora.car.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.car.client.CarSourceException;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 车型同步任务编排服务(S6 重构:异步任务化;10-15-car-image-backfill B 扩两类任务;
 * 10-21-car-sync-safety 可靠性加固)。
 *
 * <p>job_type:
 *   SELECTED / RETRY        —— 全量 syncOne
 *   IMAGE_BACKFILL          —— 轻回填:仅图片域 + 指纹收敛,不触参数/清洗/向量
 *   INCREMENTAL             —— 增量分派:按基础/图片/参数三类指纹决定 跳过/只更新图片/只更新参数/全量
 *   VECTOR_REBUILD          —— 全库逐车型重建向量(10-24 运维任务化;失败只进任务明细,不写车型同步状态)
 *   CLEAN_ONE               —— 单车型补清洗,allowAi=true(10-24 详情页任务化;区别于批量 CLEAN_BACKFILL 的 false)
 *   (SCHEDULED 为旧定时类型历史值)
 *
 * <p>10-21 关键语义:
 * <ul>
 *   <li><b>提交后触发</b>:任务行提交后才启动 @Async,避免异步线程读不到未提交行导致永久 RUNNING;</li>
 *   <li><b>互斥</b>:创建任务抢全局锁({@link CarSyncLockService#GLOBAL_JOB_KEY}) + 已有新鲜 RUNNING 拒绝;
 *       逐车型抢 {@code goods:<id>} 锁,详情页阻塞同步同口径;</li>
 *   <li><b>心跳/取消</b>:逐车型更新 heartbeat_at;cancel_requested=1 停止后续车型;</li>
 *   <li><b>计数</b>:processed = success + skipped + failed;增量跳过/空数据回填计入 skipped,不冒充 success;</li>
 *   <li><b>终态保证</b>:外层 try/finally,任何异常都落终态;旧线程不覆盖已终态任务。</li>
 * </ul>
 *
 * <p>明细结构:失败条目 {goodsId,name,error};备注条目 {goodsId,name,note(如「官网无图」「跳过:指纹一致」)}。
 */
@Slf4j
@Service
public class CarSyncJobService {

    /** 单次同步车型上限(防一次请求拖垮官网/任务)。 */
    static final int MAX_BATCH = 100;

    private final CarSyncJobMapper jobMapper;
    private final CarModelMapper modelMapper;
    private final CarModelService modelService;
    private final ObjectMapper json;
    // 自注入代理,确保 @Async 生效(createJob 内 this.runJob 不会走代理)
    @Autowired
    @Lazy
    private CarSyncJobService self;
    /** 互斥锁(字段注入:保持既有构造器签名,单测可不注入→降级为无锁)。 */
    @Autowired(required = false)
    private CarSyncLockService lockService;
    /** 补清洗 job 使用；字段注入避免破坏既有构造器单测。 */
    @Autowired(required = false)
    private CarCleanRepairService cleanRepairService;

    public CarSyncJobService(CarSyncJobMapper jobMapper, CarModelMapper modelMapper,
                             CarModelService modelService, ObjectMapper json) {
        this.jobMapper = jobMapper;
        this.modelMapper = modelMapper;
        this.modelService = modelService;
        this.json = json;
    }

    /** 创建同步任务(同步调用,带 SecurityContext)。返回 jobId。jobType: SELECTED/RETRY。 */
    @Transactional
    public Long createJob(List<String> goodsIds, String jobType) {
        List<String> ids = normalizeGoodsIds(goodsIds);
        // 手动创建前也做陈旧自愈:否则进程崩溃遗留的 RUNNING/全局锁会让新任务一直 409。
        // 独立事务:外层 createJob 因锁冲突回滚时,清理结果不能一起回滚。
        (self == null ? this : self).markStaleRunningAsFailed();
        if (hasFreshRunning()) {
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        return insertJobAndRun(ids, jobType == null ? "SELECTED" : jobType);
    }

    /** 创建「图片补入库」任务(默认只补缺失/失败/过期;FULL=true 才全库重扫)。 */
    @Transactional
    public Long createBackfillJob() {
        return createBackfillJob(false);
    }

    /**
     * 创建图片补入库任务。
     *
     * @param full false = 仅缺失/失败/过期图片(封面为空、图集为空、source_state=STALE);
     *             图片指纹未收敛(上次图片失败)的行由增量图片分支重试,或管理员用 FULL 全量重扫。
     *             true = 全库车型重扫(管理员显式选择)
     */
    @Transactional
    public Long createBackfillJob(boolean full) {
        return createBackfillJob(full, null);
    }

    /**
     * 创建图片补入库任务(10-24 定向化 R3)。
     *
     * @param full    旧过滤开关,仅在 goodsIds 为空时生效
     * @param goodsIds 非空 = 定向模式:只处理指定车型(忽略 full 过滤,显式选择即目标,
     *                 含 EMPTY 车型走存量 URL 兜底);每个 goodsId 须在库内存在,未知 → 400。
     *                 null/空 = 旧行为(full ? 全库 : 缺失/失败/过期过滤),既有调用方零感知。
     */
    @Transactional
    public Long createBackfillJob(boolean full, List<String> goodsIds) {
        List<String> targets;
        if (goodsIds != null && !goodsIds.isEmpty()) {
            // 定向模式:显式选择即目标,忽略 full 过滤;先归一(去重/上限/格式)再逐一校验存在
            targets = normalizeGoodsIds(goodsIds);
            Set<String> known = new LinkedHashSet<>();
            for (CarModelEntity m : modelMapper.selectList(
                    new QueryWrapper<CarModelEntity>().select("goods_id").in("goods_id", targets))) {
                if (m.getGoodsId() != null) known.add(m.getGoodsId());
            }
            for (String id : targets) {
                if (!known.contains(id)) throw new IllegalArgumentException("车型不存在: " + id);
            }
        } else {
            QueryWrapper<CarModelEntity> qw = new QueryWrapper<CarModelEntity>().select("id", "goods_id");
            if (!full) {
                qw.and(w -> w.isNull("cover_image_id")
                        .or().isNull("intro_images")
                        .or().eq("intro_images", "")
                        .or().eq("intro_images", "[]")
                        .or().eq("source_state", "STALE"));
            }
            List<CarModelEntity> rows = modelMapper.selectList(qw.orderByAsc("id"));
            List<String> goodsIdsAll = new ArrayList<>();
            for (CarModelEntity m : rows) {
                if (m.getGoodsId() != null && !m.getGoodsId().isBlank()) goodsIdsAll.add(m.getGoodsId());
            }
            if (goodsIdsAll.isEmpty()) throw new IllegalArgumentException("库内没有可回填的车型");
            targets = normalizeGoodsIds(goodsIdsAll);
        }
        (self == null ? this : self).markStaleRunningAsFailed();
        if (hasFreshRunning()) {
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        return insertJobAndRun(targets, "IMAGE_BACKFILL");
    }

    /**
     * 创建「重建全部向量」异步任务(10-24 车型运维 P0 R1:旧同步接口 57 台 × 十几秒必超时,任务化)。
     * 目标 = 全部 goods_id 非空车型(按 id 升序);全库运维动作不套用单次 100 上限,
     * 心跳逐车型刷新保证长任务不被陈旧自愈误杀。
     */
    @Transactional
    public Long createRebuildJob() {
        List<CarModelEntity> rows = modelMapper.selectList(
                new QueryWrapper<CarModelEntity>().select("id", "goods_id").orderByAsc("id"));
        // LinkedHashSet 保序去重(防御 goods_id 重复行);空库/无 goodsId → 400
        Set<String> ids = new LinkedHashSet<>();
        for (CarModelEntity m : rows) {
            if (m.getGoodsId() != null && !m.getGoodsId().isBlank()) ids.add(m.getGoodsId());
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("库内没有可重建向量的车型");
        (self == null ? this : self).markStaleRunningAsFailed();
        if (hasFreshRunning()) {
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        return insertJobAndRun(new ArrayList<>(ids), "VECTOR_REBUILD");
    }

    /**
     * 创建单车型清洗任务(10-24 车型运维 P0 R2,job_type=CLEAN_ONE):详情页「重新清洗」迁移入口。
     * allowAi=true 与旧阻塞 cleanOne 同语义(区别于批量 CLEAN_BACKFILL 的 false)。
     */
    @Transactional
    public Long createCleanOneJob(String goodsId) {
        if (goodsId == null || goodsId.isBlank()) throw new IllegalArgumentException("车型不存在");
        CarModelEntity m = modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", goodsId));
        if (m == null) throw new IllegalArgumentException("车型不存在");
        List<String> ids = normalizeGoodsIds(List.of(goodsId));
        (self == null ? this : self).markStaleRunningAsFailed();
        if (hasFreshRunning()) {
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        return insertJobAndRun(ids, "CLEAN_ONE");
    }

    /** 创建异步补清洗任务。force=false 只补有参数无清洗；force=true 重清洗全部有参数车型。 */
    @Transactional
    public Long createCleanJob(boolean force) {
        if (cleanRepairService == null) throw new IllegalStateException("补清洗服务未装配");
        (self == null ? this : self).markStaleRunningAsFailed();
        if (hasFreshRunning()) {
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        List<String> goodsIds = cleanRepairService.listTargetGoodsIds(force);
        if (goodsIds.isEmpty()) throw new IllegalArgumentException("没有可补清洗的车型");
        return insertJobAndRun(normalizeGoodsIds(goodsIds), "CLEAN_BACKFILL");
    }

    /** 去重/去空/长度与上限校验;非法输入抛 IllegalArgumentException(接口转 400)。 */
    private static List<String> normalizeGoodsIds(List<String> goodsIds) {
        if (goodsIds == null || goodsIds.isEmpty()) throw new IllegalArgumentException("请选择要同步的车型");
        Set<String> set = new LinkedHashSet<>();
        for (String raw : goodsIds) {
            if (raw == null) continue;
            String v = raw.trim();
            if (v.isEmpty()) continue;
            if (!v.matches("\\d{1,20}")) throw new IllegalArgumentException("车型 ID 不合法: " + v);
            set.add(v);
        }
        if (set.isEmpty()) throw new IllegalArgumentException("请选择要同步的车型");
        if (set.size() > MAX_BATCH) throw new IllegalArgumentException("单次最多同步 " + MAX_BATCH + " 个车型");
        return new ArrayList<>(set);
    }

    /** 落任务行并在事务提交后触发异步执行。 */
    private Long insertJobAndRun(List<String> goodsIds, String jobType) {
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setJobType(jobType);
        job.setStatus("RUNNING");
        job.setTotal(goodsIds.size());
        job.setSuccess(0);
        job.setFailed(0);
        job.setSkipped(0);
        job.setStartedAt(LocalDateTime.now());
        job.setHeartbeatAt(LocalDateTime.now());
        job.setCancelRequested(0);
        job.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        // 全局锁 owner 用稳定 job:<id>,异步线程收尾时可按同一 owner 释放
        String owner = "job:" + job.getId();
        if (lockService != null && lockService.tryLock(CarSyncLockService.GLOBAL_JOB_KEY, owner) == null) {
            // 另一个任务已持锁:本任务直接落 FAILED(不留 RUNNING 假象),接口返回冲突
            job.setStatus("FAILED");
            job.setFinishedAt(LocalDateTime.now());
            job.setErrorMsg("已有同步任务运行中");
            jobMapper.updateById(job);
            throw new IllegalStateException("已有同步任务运行中,请稍后重试");
        }
        triggerAfterCommit(job.getId(), goodsIds);
        return job.getId();
    }

    /**
     * 事务提交后再触发 @Async(10-21):异步线程若在事务提交前 selectById,会读不到任务行而直接退出,
     * 任务永久 RUNNING。单测 self==null 时退化为同步直调(保持既有用例可控)。
     */
    private void triggerAfterCommit(Long jobId, List<String> goodsIds) {
        if (self == null) {
            this.runJob(jobId, goodsIds);
            return;
        }
        List<String> copy = new ArrayList<>(goodsIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.runJob(jobId, copy);
                }
            });
        } else {
            self.runJob(jobId, copy);
        }
    }

    /** 异步执行任务:按 job_type 分派(全量/轻回填/增量/重建向量/单台清洗),统计 成功/跳过/失败,写明细。 */
    @Async
    public void runJob(Long jobId, List<String> goodsIds) {
        CarSyncJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            log.error("同步任务 {} 不存在(提交后异步触发仍读不到),放弃执行", jobId);
            releaseGlobal("job:" + jobId);
            return;
        }
        if (!"RUNNING".equals(job.getStatus())) {
            log.warn("同步任务 {} 已是终态 {},跳过执行", jobId, job.getStatus());
            releaseGlobal("job:" + jobId);
            return;
        }
        // 心跳占位:仅 RUNNING 可进入(同时刷新心跳,防陈旧误判)
        int locked = jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING")
                .set("heartbeat_at", LocalDateTime.now()));
        if (locked == 0) {
            log.warn("同步任务 {} 已被并发处理或已终态,跳过", jobId);
            releaseGlobal("job:" + jobId);
            return;
        }
        String type = job.getJobType() == null ? "SELECTED" : job.getJobType();
        List<Map<String, Object>> detailItems = new ArrayList<>();
        int success = 0, failed = 0, skipped = 0;
        int aggRule = 0, aggAi = 0, aggFallback = 0;
        boolean cancelled = false;
        try {
            for (String goodsId : goodsIds) {
                if (!stillRunning(jobId)) {
                    log.warn("同步任务 {} 已非 RUNNING,停止后续车型(旧线程退出)", jobId);
                    break;
                }
                if (cancelRequested(jobId)) {
                    cancelled = true;
                    log.warn("同步任务 {} 收到取消请求,停止后续车型", jobId);
                    break;
                }
                String lockOwner = lockService == null ? null : lockService.tryLock(CarSyncLockService.goodsKey(goodsId));
                if (lockService != null && lockOwner == null) {
                    skipped++;
                    appendNote(detailItems, goodsId, "跳过:该车型正在同步");
                    updateProgress(job, success, failed, skipped);
                    continue;
                }
                try {
                    if ("IMAGE_BACKFILL".equals(type)) {
                        CarModelService.IncrementalOutcome o = modelService.backfillImages(goodsId);
                        appendNote(detailItems, goodsId, o.note());
                        boolean noSource = o.outcome() != null && o.outcome().model() != null
                                && "EMPTY".equals(o.outcome().model().getSourceState());
                        if (noSource) {
                            skipped++;   // 官网无数据不算成功同步(R1)
                        } else {
                            success++;
                            appendPartialNote(detailItems, goodsId, o.outcome());
                        }
                        log.info("车型图片回填完成 goodsId={} note={} sourceState={}",
                                goodsId, o.note() == null ? "-" : o.note(),
                                o.outcome() == null || o.outcome().model() == null ? "-" : o.outcome().model().getSourceState());
                    } else if ("CLEAN_BACKFILL".equals(type)) {
                        if (cleanRepairService == null) throw new IllegalStateException("补清洗服务未装配");
                        CarCleanRepairService.CleanRepairResult r =
                                cleanRepairService.cleanOneByGoodsId(goodsId, false);
                        if (r.cleansAfter() > 0) {
                            success++;
                            appendNote(detailItems, goodsId, "补清洗 " + r.cleansAfter() + " 条");
                        } else {
                            skipped++;
                            appendNote(detailItems, goodsId, "跳过:无可清洗参数/版本");
                        }
                    } else if ("INCREMENTAL".equals(type)) {
                        CarModelService.IncrementalOutcome o = modelService.dispatchIncremental(goodsId);
                        appendNote(detailItems, goodsId, o.note());
                        boolean noSource = o.outcome() != null && o.outcome().model() != null
                                && "EMPTY".equals(o.outcome().model().getSourceState());
                        if (o.outcome() == null || noSource) {
                            skipped++;   // 跳过/官网无数据不冒充成功
                            log.info("车型增量跳过 goodsId={} note={} sourceState={}", goodsId, o.note(),
                                    noSource ? "EMPTY" : "-");
                        } else {
                            CleanStats st = o.outcome().cleanStats();
                            if (st != null) {
                                aggRule += st.getRule();
                                aggAi += st.getAi();
                                aggFallback += st.getFallback();
                            }
                            success++;
                            appendPartialNote(detailItems, goodsId, o.outcome());
                            log.info("车型增量同步完成 goodsId={} note={} 清洗统计 RULE={} AI={} FALLBACK={}",
                                    goodsId, o.note() == null ? "-" : o.note(),
                                    st == null ? 0 : st.getRule(), st == null ? 0 : st.getAi(), st == null ? 0 : st.getFallback());
                        }
                    } else if ("VECTOR_REBUILD".equals(type)) {
                        // 运维域(10-24):向量失败只进任务明细(可重试),不写车型同步状态
                        EmbedStats st = modelService.rebuildOneByGoodsId(goodsId);
                        if (st != null && st.failed() > 0) {
                            failed++;
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("goodsId", goodsId);
                            item.put("name", modelName(goodsId));
                            item.put("error", "向量失败 " + st.failed() + "/" + st.total() + " 块");
                            detailItems.add(item);
                            log.warn("车型向量重建失败 goodsId={} 失败块={}/{}", goodsId, st.failed(), st.total());
                        } else if (st == null || st.total() == 0) {
                            skipped++;
                            appendNote(detailItems, goodsId, "跳过:无切块数据");
                        } else {
                            success++;
                            appendNote(detailItems, goodsId, "重建 " + st.total() + " 块向量");
                            log.info("车型向量重建完成 goodsId={} total={} success={}",
                                    goodsId, st.total(), st.success());
                        }
                    } else if ("CLEAN_ONE".equals(type)) {
                        // 运维域(10-24):allowAi=true(显式单台重跑允许 AI;批量 CLEAN_BACKFILL 为 false);
                        // 失败只进任务明细,不写车型同步状态
                        if (cleanRepairService == null) throw new IllegalStateException("补清洗服务未装配");
                        CarCleanRepairService.CleanRepairResult r =
                                cleanRepairService.cleanOneByGoodsId(goodsId, true);
                        if (r.cleansAfter() > 0) {
                            success++;
                            appendNote(detailItems, goodsId, "补清洗 " + r.cleansAfter() + " 条");
                        } else {
                            skipped++;
                            appendNote(detailItems, goodsId, "跳过:无可清洗参数/版本");
                        }
                        log.info("车型单台清洗完成 goodsId={} cleans {}→{}",
                                goodsId, r.cleansBefore(), r.cleansAfter());
                    } else {
                        markSyncing(goodsId);
                        CarModelService.SyncOutcome outcome = modelService.syncOne(goodsId);
                        CleanStats st = outcome.cleanStats();
                        if (st != null) {
                            aggRule += st.getRule();
                            aggAi += st.getAi();
                            aggFallback += st.getFallback();
                        }
                        log.info("车型同步完成 goodsId={} name={} 清洗统计 RULE={} AI={} FALLBACK={}",
                                goodsId, outcome.model().getName(), st == null ? 0 : st.getRule(),
                                st == null ? 0 : st.getAi(), st == null ? 0 : st.getFallback());
                        success++;
                        appendPartialNote(detailItems, goodsId, outcome);
                    }
                } catch (Exception e) {
                    failed++;
                    String err = CarSourceException.classify(e);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("goodsId", goodsId);
                    item.put("name", modelName(goodsId));
                    item.put("error", err);
                    detailItems.add(item);
                    log.warn("车型同步失败 goodsId={}: {}", goodsId, err, e);
                    // 空/不完整响应已由 CarModelService 标记 source_state;其余失败回写 FAILED,避免永久 SYNCING。
                    // VECTOR_REBUILD/CLEAN_ONE 为独立运维域(10-24):异常只进任务明细,不改车型同步状态。
                    boolean opsDomain = "VECTOR_REBUILD".equals(type) || "CLEAN_ONE".equals(type);
                    if (!opsDomain && !(e instanceof com.sparkora.car.client.SourceIncompleteException)) {
                        try {
                            modelService.markSyncFailed(goodsId, err);
                        } catch (Exception ex) {
                            log.warn("车型失败状态回写失败(忽略) goodsId={}: {}", goodsId, ex.getMessage());
                        }
                    }
                } finally {
                    if (lockService != null) lockService.unlock(CarSyncLockService.goodsKey(goodsId), lockOwner);
                }
                updateProgress(job, success, failed, skipped);
            }
        } catch (Exception e) {
            // 任何未预期异常都不得让任务永久 RUNNING(error_msg 列宽 1000,先截断防写库失败)
            job.setErrorMsg(truncate(CarSourceException.classify(e), 1000));
            log.error("同步任务 {} 执行异常: {}", jobId, e.getMessage(), e);
        } finally {
            log.info("同步任务完成 jobId={} type={} success={} skipped={} failed={} 清洗聚合 total={} RULE={} AI={} FALLBACK={}",
                    jobId, type, success, skipped, failed, aggRule + aggAi + aggFallback, aggRule, aggAi, aggFallback);
            try {
                finish(job, success, failed, skipped, detailItems, cancelled);
            } catch (Exception e) {
                log.error("同步任务 {} 收尾失败,尝试兜底终态: {}", jobId, e.getMessage(), e);
                try {
                    jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                            .eq("id", jobId).eq("status", "RUNNING")
                            .set("status", "FAILED")
                            .set("finished_at", LocalDateTime.now())
                            .set("error_msg", truncate(CarSourceException.classify(e), 1000)));
                } catch (Exception ex) {
                    log.error("同步任务 {} 兜底终态写入失败: {}", jobId, ex.getMessage(), ex);
                }
            } finally {
                // finish 自身异常也必须释放全局锁,否则后续任务会被永久挡住。
                releaseGlobal("job:" + jobId);
            }
        }
    }

    /** 任务是否仍为 RUNNING(旧线程写前校验,防止把终态写回 RUNNING)。 */
    private boolean stillRunning(Long jobId) {
        CarSyncJobEntity fresh = jobMapper.selectById(jobId);
        return fresh != null && "RUNNING".equals(fresh.getStatus());
    }

    private boolean cancelRequested(Long jobId) {
        CarSyncJobEntity fresh = jobMapper.selectById(jobId);
        return fresh != null && Integer.valueOf(1).equals(fresh.getCancelRequested());
    }

    /**
     * 进度写回 + 心跳;异常仅告警,不中断任务。
     * 用条件 UpdateWrapper 只写计数/心跳,不整实体写回——否则会把并发写入的
     * cancel_requested=1 覆盖回 0、或把终态 status 写回 RUNNING。
     */
    private void updateProgress(CarSyncJobEntity job, int success, int failed, int skipped) {
        job.setSuccess(success);
        job.setFailed(failed);
        job.setSkipped(skipped);
        job.setHeartbeatAt(LocalDateTime.now());
        try {
            jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                    .eq("id", job.getId()).eq("status", "RUNNING")
                    .set("success", success)
                    .set("failed", failed)
                    .set("skipped", skipped)
                    .set("heartbeat_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("同步任务进度写回失败(忽略) jobId={}: {}", job.getId(), e.getMessage());
        }
    }

    /**
     * 车型部分失败(图片/向量/版本参数不完整)时补一条带 {@code partial=true} 的明细,
     * 让任务终态可判 PARTIAL。不能靠 note 文案里是否含「失败」判断——如「版本不完整」
     * 不含该词,会把 PARTIAL 车型的任务误判为 SUCCESS。
     */
    private void appendPartialNote(List<Map<String, Object>> detailItems, String goodsId,
                                   CarModelService.SyncOutcome outcome) {
        if (outcome == null || outcome.model() == null) return;
        if ("PARTIAL".equals(outcome.model().getSyncStatus())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("goodsId", goodsId);
            item.put("name", modelName(goodsId));
            item.put("note", outcome.model().getSyncNote() == null
                    ? "部分失败(保留旧数据)" : outcome.model().getSyncNote());
            item.put("partial", true);
            detailItems.add(item);
        }
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

    /**
     * 任务收尾:置终态(SUCCESS/PARTIAL/FAILED)并写明细(失败+备注)。
     * 用 `WHERE status='RUNNING'` 条件写,保证陈旧清理/取消等并发写下的终态不被旧线程覆盖
     * (先 select 再无条件 update 存在 TOCTOU,见 database-guidelines「原子抢占」)。
     */
    protected void finish(CarSyncJobEntity job, int success, int failed, int skipped,
                          List<Map<String, Object>> detailItems, boolean cancelled) {
        if (job.getId() == null) return;
        if (cancelled && job.getErrorMsg() == null) job.setErrorMsg("用户取消");
        boolean partialNote = detailItems.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("partial")));
        String status;
        if (failed == 0 && !partialNote && !cancelled) {
            status = "SUCCESS";
        } else if (success + skipped > 0) {
            status = "PARTIAL";
        } else {
            status = "FAILED";
        }
        LocalDateTime now = LocalDateTime.now();
        job.setStatus(status);
        job.setSuccess(success);
        job.setFailed(failed);
        job.setSkipped(skipped);
        job.setFinishedAt(now);
        job.setHeartbeatAt(now);
        job.setFailedItems(toJson(detailItems));
        int updated = jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                .eq("id", job.getId()).eq("status", "RUNNING")
                .set("status", status)
                .set("success", success)
                .set("failed", failed)
                .set("skipped", skipped)
                .set("finished_at", now)
                .set("heartbeat_at", now)
                .set("failed_items", job.getFailedItems())
                .set("error_msg", job.getErrorMsg()));
        if (updated == 0) {
            log.warn("同步任务 {} 已是终态或不存在,旧线程不覆盖", job.getId());
        }
    }

    /** 查询任务(含失败明细解析)。 */
    public CarSyncJobEntity get(Long id) {
        return jobMapper.selectById(id);
    }

    /** 最近任务列表(按创建时间倒序)。 */
    public List<CarSyncJobEntity> list() {
        return jobMapper.selectList(new QueryWrapper<CarSyncJobEntity>().orderByDesc("id"));
    }

    /** 陈旧阈值(60 分钟):全量 56 车型 + 清洗 + embedding 实测可超 20 分钟。 */
    private static final long SYNC_STALE_MS = 60 * 60 * 1000L;

    /** 是否存在「未过期」的运行中任务:心跳优先,缺失时回退 started_at。 */
    public boolean hasFreshRunning() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        return jobMapper.selectCount(new QueryWrapper<CarSyncJobEntity>()
                .eq("status", "RUNNING")
                .and(w -> w.ge("heartbeat_at", cutoff)
                        .or(w2 -> w2.isNull("heartbeat_at").ge("started_at", cutoff)))) > 0;
    }

    /**
     * 将陈旧 RUNNING 原子置为 FAILED:心跳(或 started_at)早于阈值。
     * 心跳每车型更新一次,活任务不会因总时长超 60 分钟被误杀。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markStaleRunningAsFailed() {
        LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(SYNC_STALE_MS));
        // 先取 ID,清理后释放其全局锁;否则进程崩溃遗留的 job:<id> 锁会挡住新任务直到 TTL 过期。
        List<CarSyncJobEntity> stale = jobMapper.selectList(new QueryWrapper<CarSyncJobEntity>()
                .eq("status", "RUNNING")
                .and(w -> w.lt("heartbeat_at", cutoff)
                        .or(w2 -> w2.isNull("heartbeat_at").lt("started_at", cutoff))));
        int updated = jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                .eq("status", "RUNNING")
                .and(w -> w.lt("heartbeat_at", cutoff)
                        .or(w2 -> w2.isNull("heartbeat_at").lt("started_at", cutoff)))
                .set("status", "FAILED")
                .set("finished_at", LocalDateTime.now())
                .set("error_msg", "运行超时判定为陈旧,自动终止"));
        if (updated > 0) log.warn("清理陈旧运行中同步任务 {} 条(超 {} 分钟)", updated, SYNC_STALE_MS / 60000);
        for (CarSyncJobEntity j : stale) {
            if (j.getId() != null) releaseGlobal("job:" + j.getId());
        }
        return updated;
    }

    /** 请求取消运行中任务:置 cancel_requested=1,当前车型结束后停止后续。 */
    public boolean cancel(Long jobId) {
        int n = jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING")
                .set("cancel_requested", 1));
        return n > 0;
    }

    /** 详情页阻塞同步:与任务逐车型锁同口径,避免与异步任务并发写同一车型。 */
    public CarModelService.SyncOutcome syncOneExclusive(String goodsId) {
        String owner = lockService == null ? null : lockService.tryLock(CarSyncLockService.goodsKey(goodsId));
        if (lockService != null && owner == null) {
            throw new IllegalStateException("该车型正在同步,请稍后重试");
        }
        try {
            return modelService.syncOne(goodsId);
        } finally {
            if (lockService != null) lockService.unlock(CarSyncLockService.goodsKey(goodsId), owner);
        }
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void releaseGlobal(String owner) {
        if (lockService != null && owner != null) lockService.unlock(CarSyncLockService.GLOBAL_JOB_KEY, owner);
    }
}
