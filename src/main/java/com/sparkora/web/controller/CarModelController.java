package com.sparkora.web.controller;

import com.sparkora.car.dto.CarModelDetailDto;
import com.sparkora.car.service.CarCleanRepairService;
import com.sparkora.car.service.CarModelService;
import com.sparkora.car.service.CarRagService;
import com.sparkora.car.service.CarSyncJobService;
import com.sparkora.common.R;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarSyncJobEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 车型知识库接口。
 * - VIEWER 可读;ADMIN/EDITOR 可同步/删除。
 * - sync:手动触发采集入库(异步任务化,创建任务即返回 jobId,前端轮询进度)。
 * - rag:内部问答检索(返回命中的知识块)。
 */
@Slf4j
@RestController
@RequestMapping("/api/car")
public class CarModelController {

    private final CarModelService service;
    private final CarRagService ragService;
    private final CarSyncJobService jobService;
    private final CarCleanRepairService cleanRepairService;

    public CarModelController(CarModelService service, CarRagService ragService, CarSyncJobService jobService,
                              CarCleanRepairService cleanRepairService) {
        this.service = service;
        this.ragService = ragService;
        this.jobService = jobService;
        this.cleanRepairService = cleanRepairService;
    }

    @GetMapping("/models")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<CarModelEntity>> list() {
        return R.ok(service.list());
    }

    /** 官网车型目录(供同步页手动选择)。返回 goodsListForSearch 原始 data 数组。 */
    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<Map<String, Object>>> catalog() {
        try {
            return R.ok(service.catalog());
        } catch (Exception e) {
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /**
     * 创建同步任务。body: {goodsIds:["156","10051"]}。返回 {jobId}。
     * 10-21:goodsIds 去重/上限/格式校验失败 → 400;已有运行中任务 → 409。
     */
    @PostMapping("/sync/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> createJob(@RequestBody Map<String, List<String>> body) {
        try {
            Long jobId = jobService.createJob(body == null ? null : body.get("goodsIds"), "SELECTED");
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            log.warn("创建车型同步任务失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /**
     * 创建「图片补入库」任务(10-15-car-image-backfill R1 存量回填;无参,覆盖全部库内存活车型)。
     * 逐车型轻回填:仅图片域(下载/哈希去重/图床/自动打标)+ 指纹收敛,不触参数/清洗/向量。
     * 返回 jobId(前端复用任务轮询面板看进度/明细;空图车型明细标注「官网无图」)。
     */
    @PostMapping("/sync/backfill")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Long> createBackfillJob(@RequestParam(required = false, defaultValue = "MISSING") String mode) {
        try {
            // 10-21:默认只补缺失/失败/过期图片;FULL 需显式指定(全库重扫较慢)
            return R.ok(jobService.createBackfillJob("FULL".equalsIgnoreCase(mode)));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());   // 参数/前置错误(如库内无可回填车型)
        } catch (IllegalStateException ex) {
            return R.fail(409, ex.getMessage());
        } catch (Exception e) {
            log.warn("创建车型图片回填任务失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 查询同步任务进度。 */
    @GetMapping("/sync/jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<CarSyncJobEntity> getJob(@PathVariable Long id) {
        CarSyncJobEntity job = jobService.get(id);
        if (job == null) return R.fail(404, "任务不存在");
        return R.ok(job);
    }

    /** 同步任务历史列表。 */
    @GetMapping("/sync/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<CarSyncJobEntity>> listJobs() {
        return R.ok(jobService.list());
    }

    /** 重试任务的失败项。返回新任务 {jobId}。 */
    @PostMapping("/sync/jobs/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> retryJob(@PathVariable Long id) {
        try {
            Long jobId = jobService.retry(id);
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            log.warn("重试车型同步任务失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 请求取消运行中任务(停止处理后续车型;已开始的车型会跑完)。 */
    @PostMapping("/sync/jobs/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> cancelJob(@PathVariable Long id) {
        CarSyncJobEntity job = jobService.get(id);
        if (job == null) return R.fail(404, "任务不存在");
        return jobService.cancel(id) ? R.ok() : R.fail(409, "任务不在运行中,无法取消");
    }

    @GetMapping("/models/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<CarModelDetailDto> detail(@PathVariable Long id,
                                       @RequestParam(required = false) Long versionId) {
        return R.ok(service.detail(id, versionId));
    }

    /** 全量同步已取消(S6 重构:仅手动指定车型同步)。 */
    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> syncAll() {
        return R.fail(400, "全量同步已取消,请在同步页选择车型后同步");
    }

    /**
     * 同步单个车型(按 goodsId)。返回车型与清洗统计摘要。
     * 10-21 R2:阻塞式同步失败同样回写车型 FAILED + 原因(与异步任务 catch 同口径),
     * 否则详情页同步失败后卡片/详情仍停在旧状态、看不到原因。
     */
    @PostMapping("/models/{id}/sync")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> syncOne(@PathVariable Long id) {
        CarModelEntity m;
        try {
            m = service.get(id);
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
        if (m == null) return R.fail(404, "车型不存在");
        try {
            // 与异步任务逐车型锁同口径:同一车型同时只允许一个同步
            CarModelService.SyncOutcome outcome = jobService.syncOneExclusive(m.getGoodsId());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("model", outcome.model());
            data.put("cleanStats", outcome.cleanStats());
            return R.ok(data);
        } catch (IllegalStateException e) {
            // 只把「车型锁冲突」映射为 409;其它 IllegalStateException 仍按失败处理
            if (e.getMessage() != null && e.getMessage().contains("正在同步")) return R.fail(409, e.getMessage());
            String err = com.sparkora.car.client.CarSourceException.classify(e);
            try {
                service.markSyncFailed(m.getGoodsId(), err);
            } catch (Exception ex) {
                log.warn("车型失败状态回写失败(忽略) goodsId={}: {}", m.getGoodsId(), ex.getMessage());
            }
            return R.fail(500, err);
        } catch (Exception e) {
            String err = com.sparkora.car.client.CarSourceException.classify(e);
            // 回写自身失败仅告警,不覆盖原始错误/响应
            try {
                service.markSyncFailed(m.getGoodsId(), err);
            } catch (Exception ex) {
                log.warn("车型失败状态回写失败(忽略) goodsId={}: {}", m.getGoodsId(), ex.getMessage());
            }
            return R.fail(500, err);
        }
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return R.ok();
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 单车型补清洗(仅基于库内参数重跑,不请求官网)。 */
    @PostMapping("/models/{id}/clean")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<CarCleanRepairService.CleanRepairResult> cleanOne(@PathVariable Long id) {
        try {
            return R.ok(cleanRepairService.cleanOne(id));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            log.warn("单车型补清洗失败 id={}: {}", id, e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 全库补清洗:只处理「有原始参数、无清洗结果」的车型,不请求官网。 */
    @PostMapping("/models/clean-missing")
    @PreAuthorize("hasRole('ADMIN')")
    public R<CarCleanRepairService.CleanRepairSummary> cleanMissing() {
        try {
            return R.ok(cleanRepairService.cleanMissing());
        } catch (Exception e) {
            log.warn("全库补清洗失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 创建异步补清洗任务；force=true 重清洗全部有参数车型（清理历史 null-version 行）。 */
    @PostMapping("/models/clean-jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> createCleanJob(@RequestParam(required = false, defaultValue = "false") boolean force) {
        try {
            Long jobId = jobService.createCleanJob(force);
            return R.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            log.warn("创建补清洗任务失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 一次性回填官网 goodsInfo.createdAt（UI「创建时间」；只读，不动业务数据）。 */
    @PostMapping("/models/backfill-created-at")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> backfillCreatedAt() {
        try {
            return R.ok(service.backfillSourceCreatedAt());
        } catch (Exception e) {
            log.warn("回填官网创建时间失败: {}", e.getMessage(), e);
            return R.fail(500, com.sparkora.car.client.CarSourceException.classify(e));
        }
    }

    /** 单车型清洗质量统计(可观测):按 clean_method 分组计数 + 可疑 STRING 兜底占比。 */
    @GetMapping("/models/{id}/clean-stats")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> cleanStats(@PathVariable Long id) {
        try {
            return R.ok(service.cleanStats(id));
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 全库向量对账统计(可观测):块数/有向量数/缺失数+缺失明细 topN。 */
    @GetMapping("/models/vector-stats")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> vectorStats() {
        try {
            return R.ok(service.vectorStats());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 批量重建全部车型向量(一次性运维操作,同步接口;耗时=车型数×块数×embedding)。 */
    @PostMapping("/models/rebuild-all")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> rebuildAll() {
        try {
            return R.ok(service.rebuildAll());
        } catch (Exception e) {
            return R.fail(500, "批量重建失败: " + e.getMessage());
        }
    }

    /** 内部问答检索。body: {modelId, query, topK?}。返回命中的知识块文本。 */
    @PostMapping("/rag")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> rag(@RequestBody Map<String, Object> body) {
        try {
            Long modelId = body.get("modelId") == null ? null : Long.valueOf(String.valueOf(body.get("modelId")));
            String query = body.get("query") == null ? "" : String.valueOf(body.get("query"));
            int topK = body.get("topK") == null ? 8 : Integer.parseInt(String.valueOf(body.get("topK")));
            var hits = ragService.retrieve(modelId, query, topK);
            return R.ok(Map.of("hits", hits));
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }
}
