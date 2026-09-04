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
 * 车型同步任务编排服务(S6 重构:异步任务化)。
 *
 * 取消全量同步,仅手动指定车型同步。流程:
 *   createJob(goodsIds) → 落任务表(RUNNING) → 返回 jobId
 *   @Async runJob(jobId) → 逐车型 syncOne → 更新任务进度/失败明细
 *
 * 并发控制:任务启动时原子更新 status(RUNNING→RUNNING 影响行数=0 则拒绝),
 * 用数据库行锁避免重复触发,无需 Redis。
 *
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

    /** 创建同步任务(同步调用,带 SecurityContext)。返回 jobId。 */
    @Transactional
    public Long createJob(List<String> goodsIds, String jobType) {
        if (goodsIds == null || goodsIds.isEmpty()) throw new IllegalArgumentException("请选择要同步的车型");
        CarSyncJobEntity job = new CarSyncJobEntity();
        job.setJobType(jobType == null ? "SELECTED" : jobType);
        job.setStatus("RUNNING");
        job.setTotal(goodsIds.size());
        job.setSuccess(0);
        job.setFailed(0);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedBy(SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        self.runJob(job.getId(), goodsIds);
        return job.getId();
    }

    /** 异步执行任务:逐车型同步,统计成功/失败,写失败明细。 */
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
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int success = 0;
        int failed = 0;
        // 清洗方式聚合(可观测:RULE/AI/FALLBACK 汇总,随任务收尾日志输出)
        int aggRule = 0, aggAi = 0, aggFallback = 0;
        for (String goodsId : goodsIds) {
            try {
                // 先置 SYNCING 中间态,再入库(入库成功 persistModel 会置 SUCCESS)
                markSyncing(goodsId);
                CarModelService.SyncOutcome outcome = modelService.syncOne(goodsId);
                CleanStats st = outcome.cleanStats();
                aggRule += st.getRule();
                aggAi += st.getAi();
                aggFallback += st.getFallback();
                log.info("车型同步完成 goodsId={} name={} 清洗统计 RULE={} AI={} FALLBACK={}",
                        goodsId, outcome.model().getName(), st.getRule(), st.getAi(), st.getFallback());
                success++;
            } catch (Exception e) {
                failed++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("goodsId", goodsId);
                item.put("name", modelName(goodsId));
                item.put("error", e.getMessage() == null ? "未知错误" : e.getMessage());
                failedItems.add(item);
                log.warn("车型同步失败 goodsId={}: {}", goodsId, e.getMessage());
            }
            // 每完成一个更新一次进度(前端轮询可见)
            job.setSuccess(success);
            job.setFailed(failed);
            jobMapper.updateById(job);
        }
        log.info("同步任务完成 jobId={} 清洗聚合 total={} RULE={} AI={} FALLBACK={} (fallbackPct={}%)",
                jobId, aggRule + aggAi + aggFallback, aggRule, aggAi, aggFallback,
                (aggRule + aggAi + aggFallback) == 0 ? 0
                        : aggFallback * 100 / (aggRule + aggAi + aggFallback));
        finish(job, success, failed, failedItems);
    }

    /** 任务收尾:置终态(SUCCESS/PARTIAL/FAILED)并写失败明细。 */
    @Transactional
    protected void finish(CarSyncJobEntity job, int success, int failed, List<Map<String, Object>> failedItems) {
        job.setSuccess(success);
        job.setFailed(failed);
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

    /** 查询任务(含失败明细解析)。 */
    public CarSyncJobEntity get(Long id) {
        return jobMapper.selectById(id);
    }

    /** 最近任务列表(按创建时间倒序)。 */
    public List<CarSyncJobEntity> list() {
        return jobMapper.selectList(new QueryWrapper<CarSyncJobEntity>().orderByDesc("id"));
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
