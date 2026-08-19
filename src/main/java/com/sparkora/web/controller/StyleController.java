package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.service.StyleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 风格库接口。
 * - VIEWER 可读；ADMIN/EDITOR 可增删改 + 提炼入库。
 * - extract：用户提供样文 + 风格名，AI 提炼为风格画像入库。
 */
@RestController
@RequestMapping("/api/styles")
public class StyleController {

    private final StyleService service;

    public StyleController(StyleService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<StyleProfileEntity>> list(@RequestParam(required = false) Boolean enabledOnly) {
        return R.ok(service.list(enabledOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<StyleProfileEntity> get(@PathVariable Long id) { return R.ok(service.get(id)); }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<StyleProfileEntity> create(@RequestBody StyleProfileEntity e) {
        return R.ok(service.create(e));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<StyleProfileEntity> update(@PathVariable Long id, @RequestBody StyleProfileEntity e) {
        e.setId(id);
        return R.ok(service.update(e));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /** 从样文提炼风格画像并入库。body: {name, sourceText} */
    @PostMapping("/extract")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<StyleProfileEntity> extract(@RequestBody Map<String, String> body) {
        try {
            return R.ok(service.extract(body.get("sourceText"), body.get("name")));
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }
}
