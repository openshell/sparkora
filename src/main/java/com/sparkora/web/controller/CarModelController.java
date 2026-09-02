package com.sparkora.web.controller;

import com.sparkora.car.dto.CarModelDetailDto;
import com.sparkora.car.service.CarModelService;
import com.sparkora.car.service.CarRagService;
import com.sparkora.common.R;
import com.sparkora.domain.entity.CarModelEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 车型知识库接口。
 * - VIEWER 可读;ADMIN/EDITOR 可同步/删除。
 * - sync:手动触发采集入库(网络+embedding,耗时较长,放宽超时)。
 * - rag:内部问答检索(返回命中的知识块)。
 */
@RestController
@RequestMapping("/api/car")
public class CarModelController {

    private final CarModelService service;
    private final CarRagService ragService;

    public CarModelController(CarModelService service, CarRagService ragService) {
        this.service = service;
        this.ragService = ragService;
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

    /** 同步选中的车型。body: {goodsIds:["156","10051"]}。 */
    @PostMapping("/sync/selected")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Integer> syncSelected(@RequestBody Map<String, List<String>> body) {
        try {
            return R.ok(service.syncSelected(body.get("goodsIds")));
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

    /** 全量同步(拉目录+全部车型入库)。耗时较长。 */
    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Integer> syncAll() {
        try {
            return R.ok(service.syncAll());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 同步单个车型(按 goodsId)。 */
    @PostMapping("/models/{id}/sync")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<CarModelEntity> syncOne(@PathVariable Long id) {
        try {
            CarModelEntity m = service.get(id);
            if (m == null) return R.fail(404, "车型不存在");
            return R.ok(service.syncOne(m.getGoodsId()));
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
