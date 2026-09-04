package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.entity.KbDocEntity;
import com.sparkora.kb.service.KbDocService;
import com.sparkora.security.SecurityUtil;
import com.sparkora.web.dto.KbDocSaveDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通用汽车知识库接口(S7 车型库泛化)。
 * - VIEWER 可读;ADMIN/EDITOR 可写(新建/编辑/删除/重建向量)。
 * - 新建/编辑自动切块+向量化;重建幂等(先清后插)。
 */
@RestController
@RequestMapping("/api/kb")
public class KbDocController {

    private final KbDocService service;

    public KbDocController(KbDocService service) {
        this.service = service;
    }

    /** 知识文档列表(标题/领域/块数/更新时间)。 */
    @GetMapping("/docs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.List<Map<String, Object>>> list() {
        return R.ok(service.list());
    }

    /** 知识文档详情(含 content)。 */
    @GetMapping("/docs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        try {
            return R.ok(service.get(id));
        } catch (IllegalArgumentException e) {
            return R.fail(404, e.getMessage());
        }
    }

    /** 新建(自动切块向量化)。 */
    @PostMapping("/docs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> create(@Valid @RequestBody KbDocSaveDto dto) {
        try {
            KbDocEntity d = service.create(dto.getTitle(), dto.getDomain(), dto.getContent(),
                    SecurityUtil.current() == null ? "system" : SecurityUtil.current().getUsername());
            return R.ok(service.get(d.getId()));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "创建失败: " + e.getMessage());
        }
    }

    /** 编辑(自动重建向量)。 */
    @PutMapping("/docs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody KbDocSaveDto dto) {
        try {
            KbDocEntity d = service.update(id, dto.getTitle(), dto.getDomain(), dto.getContent(), dto.getEnabled());
            return R.ok(service.get(d.getId()));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "更新失败: " + e.getMessage());
        }
    }

    /** 删除(逻辑删文档+物理清块)。 */
    @DeleteMapping("/docs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return R.ok();
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 手动重建向量(幂等)。返回 {total, success, failed}。 */
    @PostMapping("/docs/{id}/rebuild")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> rebuild(@PathVariable Long id) {
        try {
            KbDocService.EmbedStats st = service.rebuild(id);
            return R.ok(Map.of("total", st.total(), "success", st.success(), "failed", st.failed()));
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }
}