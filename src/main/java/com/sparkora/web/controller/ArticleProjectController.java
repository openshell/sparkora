package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.ProjectRequest;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.BriefService;
import com.sparkora.service.NotReadyException;
import com.sparkora.service.VersionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创作项目 CRUD + 生成 brief（S1 起接真实 AI）。
 */
@RestController
@RequestMapping("/api/projects")
public class ArticleProjectController {

    private final ArticleProjectMapper mapper;
    private final BriefService briefService;
    private final VersionService versionService;
    private final com.sparkora.service.ImageService imageService;

    public ArticleProjectController(ArticleProjectMapper mapper, BriefService briefService, VersionService versionService,
                                    com.sparkora.service.ImageService imageService) {
        this.mapper = mapper;
        this.briefService = briefService;
        this.versionService = versionService;
        this.imageService = imageService;
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
     * 生成 brief（S1：接真实 AI）。同步调用，前端 loading 等待。
     * 状态机 DRAFT→GENERATING_BRIEF→READY；失败回 DRAFT 并写 lastBriefError（可在 project 详情查看）。
     */
    @PostMapping("/{id}/generate/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ArticleBriefEntity> generateBrief(@PathVariable Long id) {
        ArticleProjectEntity e = mapper.selectById(id);
        if (e == null) return R.fail(404, "项目不存在");
        try {
            ArticleBriefEntity b = briefService.generate(id);
            return R.ok(b);
        } catch (IllegalStateException ex) {
            // 状态冲突(并发生成中)→ 409;状态已由 service 原样保留,前端恢复生成中视图
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            // 状态已由 service 回滚为 DRAFT；这里返回错误信息供前端展示
            return R.fail(500, ex.getMessage());
        }
    }

    /**
     * 取项目当前 brief（无则 data=null）。
     */
    @GetMapping("/{id}/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<ArticleBriefEntity> currentBrief(@PathVariable Long id) {
        return R.ok(briefService.currentBrief(id));
    }

    // ==================== 文章版本（S1b）====================

    /**
     * 生成多版本正文（基于当前 brief + 用户选择的风络）。body: {"styleIds":[1,2]}（风格库 id 列表）。
     * 每选一个风格生成一版。同步调用，前端 loading 等待（AI 耗时较长，前端单独放宽超时）。
     */
    @PostMapping("/{id}/generate/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ArticleVersionEntity>> generateVersions(@PathVariable Long id,
                                                          @RequestBody java.util.Map<String, java.util.List<Long>> body) {
        try {
            return R.ok(versionService.generate(id, body.get("styleIds")));
        } catch (NotReadyException ex) {
            // 前置状态不满足(未生成 brief 等)→ 400 客户端错误
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            // 状态冲突(并发生成中)→ 409
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 列出项目全部版本。 */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<ArticleVersionEntity>> listVersions(@PathVariable Long id) {
        return R.ok(versionService.list(id));
    }

    /** 设定当前版本（用于后续预览/发布）。 */
    @PutMapping("/{id}/current-version")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setCurrentVersion(@PathVariable Long id, @RequestParam Long versionId) {
        try {
            versionService.setCurrent(id, versionId);
            return R.ok();
        } catch (Exception ex) {
            return R.fail(400, ex.getMessage());
        }
    }

    // ==================== 配图（S3b，字段级契约见 spec §10）====================

    /** 配图快照：项目全部图 + 当前版本封面/插图（三角色可读）。 */
    @GetMapping("/{id}/images")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> projectImages(@PathVariable Long id) {
        return R.ok(imageService.projectImages(id));
    }

    /** 选封面（幂等）。 */
    @PostMapping("/{id}/images/{imageId}/cover")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setCover(@PathVariable Long id, @PathVariable Long imageId) {
        try {
            imageService.setCover(id, imageId);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 增/删正文插图（?action=add|remove，幂等）。 */
    @PostMapping("/{id}/images/{imageId}/body")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> modifyBodyImage(@PathVariable Long id, @PathVariable Long imageId,
                                   @RequestParam(defaultValue = "add") String action) {
        try {
            imageService.modifyBodyImage(id, imageId, action);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 完成配图：VERSIONS_READY→IMAGES_READY（幂等）。 */
    @PostMapping("/{id}/complete-images")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> completeImages(@PathVariable Long id) {
        try {
            imageService.completeImages(id);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }
}
