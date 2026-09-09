package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.SettingUpdateDto;
import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.SettingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 系统检索设置接口(09-09-brief-gen-redesign)。
 * 页面控制内部知识库/外部搜索启用;读 ADMIN/EDITOR,写仅 ADMIN(系统级设置影响全局生成行为)。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    /** 读取设置(单行;首次访问自动初始化默认行) */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<SettingEntity> get() {
        return R.ok(service.get());
    }

    /** 更新设置(字段 null 不改);仅 ADMIN */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<SettingEntity> update(@Valid @RequestBody SettingUpdateDto dto) {
        Long userId = SecurityUtil.current() != null ? SecurityUtil.current().getUserId() : null;
        return R.ok(service.update(dto.getKbEnabled(), dto.getWebSearchEnabled(), userId));
    }
}