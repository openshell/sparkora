package com.sparkora.web.controller;

import com.sparkora.car.dto.CarModelDetailDto;
import com.sparkora.car.service.CarModelService;
import com.sparkora.car.service.CarRagService;
import com.sparkora.car.service.CarSyncJobService;
import com.sparkora.common.R;
import com.sparkora.domain.entity.CarModelEntity;
import com.sparkora.domain.entity.CarSyncJobEntity;
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
@RestController
@RequestMapping("/api/car")
public class CarModelController {

    private final CarModelService service;
    private final CarRagService ragService;
    private final CarSyncJobService jobService;

    public CarModelController(CarModelService service, CarRagService ragService, CarSyncJobService jobService) {
        this.service = service;
        this.ragService = ragService;
        this.jobService = jobService;
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
            return R.fail(500, e.getMessage());
        }
    }

    /** 创建同步任务。body: {goodsIds:["156","10051"]}。返回 {jobId}。 */
    @PostMapping("/sync/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> createJob(@RequestBody Map<String, List<String>> body) {
        try {
            Long jobId = jobService.createJob(body.get("goodsIds"), "SELECTED");
            return R.ok(Map.of("jobId", jobId));
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
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
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
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

    /** 同步单个车型(按 goodsId)。返回车型与清洗统计摘要。 */
    @PostMapping("/models/{id}/sync")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> syncOne(@PathVariable Long id) {
        try {
            CarModelEntity m = service.get(id);
            if (m == null) return R.fail(404, "车型不存在");
            CarModelService.SyncOutcome outcome = service.syncOne(m.getGoodsId());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("model", outcome.model());
            data.put("cleanStats", outcome.cleanStats());
            return R.ok(data);
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return R.ok();
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
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
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
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
