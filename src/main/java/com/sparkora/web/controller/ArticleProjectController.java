package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.ProjectRequest;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创作项目 CRUD + 生成 brief 占位。
 */
@RestController
@RequestMapping("/api/projects")
public class ArticleProjectController {

    private final ArticleProjectMapper mapper;

    public ArticleProjectController(ArticleProjectMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<ArticleProjectEntity>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status) {

        QueryWrapper<ArticleProjectEntity> qw = new QueryWrapper<>();
        if (topic != null && !topic.isBlank()) qw.like("topic", topic);
        if (status != null && !status.isBlank()) qw.eq("status", status);
        qw.orderByDesc("updated_at");

        Page<ArticleProjectEntity> p = new Page<>(page, size);
        Page<ArticleProjectEntity> result = mapper.selectPage(p, qw);
        return R.ok(new PageResult<>(result.getRecords(), result.getTotal(), page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<ArticleProjectEntity> get(@PathVariable Long id) {
        return R.ok(mapper.selectById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Long> create(@Valid @RequestBody ProjectRequest req) {
        CurrentUser cu = SecurityUtil.require();
        ArticleProjectEntity e = new ArticleProjectEntity();
        e.setTopic(req.getTopic());
        e.setKeywords(req.getKeywords());
        e.setAudience(req.getAudience());
        e.setWordCountTarget(req.getWordCountTarget());
        e.setBrandVoiceProfileId(req.getBrandVoiceProfileId());
        e.setRemark(req.getRemark());
        e.setStatus("DRAFT");
        e.setCreatedBy(cu.getUsername());
        e.setDeleted(0);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        mapper.insert(e);
        return R.ok(e.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ProjectRequest req) {
        ArticleProjectEntity e = mapper.selectById(id);
        if (e == null) return R.fail(404, "项目不存在");
        e.setTopic(req.getTopic());
        e.setKeywords(req.getKeywords());
        e.setAudience(req.getAudience());
        e.setWordCountTarget(req.getWordCountTarget());
        e.setBrandVoiceProfileId(req.getBrandVoiceProfileId());
        e.setRemark(req.getRemark());
        e.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/{ids}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> delete(@PathVariable String ids) {
        List<Long> idList = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
        mapper.deleteBatchIds(idList);
        return R.ok();
    }

    /**
     * 生成 brief 占位（S0 不接 AI，仅推进状态）。
     */
    @PostMapping("/{id}/generate/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> generateBrief(@PathVariable Long id) {
        ArticleProjectEntity e = mapper.selectById(id);
        if (e == null) return R.fail(404, "项目不存在");
        e.setStatus("READY");  // S0 直接置 READY，S1 起接 AI 并置 GENERATING_BRIEF
        e.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(e);
        return R.ok();
    }
}
