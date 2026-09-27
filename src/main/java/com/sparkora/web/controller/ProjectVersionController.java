package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.common.R;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.service.NotReadyException;
import com.sparkora.service.VersionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目文章版本（S1b）与简报阶段标题点选子域接口。
 *
 * 09-27-split-monoliths：从 {@link ArticleProjectController} 按子域拆出，方法体逐字搬迁（零行为变化）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectVersionController {

    private final ArticleProjectMapper mapper;
    private final VersionService versionService;

    public ProjectVersionController(ArticleProjectMapper mapper, VersionService versionService) {
        this.mapper = mapper;
        this.versionService = versionService;
    }

    // ==================== 文章版本（S1b）====================

    /**
     * 生成多版本正文（基于当前 brief + 用户选择的风格）。body: {"styleIds":[1,2]}（风格库 id 列表）。
     * 每选一个风格生成一版。09-27-gen-async 异步化:同步毫秒级返回占位标记,后台 @Async 执行 AI;
     * 前端靠项目状态轮询(GENERATING_VERSIONS→VERSIONS_READY)翻转刷新。
     * 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):主题创作项目封死(深度版本走 POST /api/deep/{id}/generate);
     * 文章仿写(09-09-article-imitation，docs/spec/imitation.md)例外:genSource=IMITATION 时本接口复用为仿写生成
     * (多风格一次生成,产出仿写正文+相似度自检,状态机同 docs/spec/overview.md)。
     */
    @PostMapping("/generate/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<java.util.Map<String, Object>> generateVersions(@PathVariable Long projectId,
                                                          @RequestBody java.util.Map<String, java.util.List<Long>> body) {
        // 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):主题创作项目恒 410(深度单版走 /deep/generate);
        // 文章仿写(09-09-article-imitation，docs/spec/imitation.md)例外放行:复用本接口多风格一次生成(仿写 prompt+去图+相似度自检)。
        ArticleProjectEntity p = mapper.selectById(projectId);
        if (p == null) return R.fail(404, "项目不存在");
        if (!"IMITATION".equals(p.getGenSource())) {
            return R.fail(410, "生成流程已升级为深度模式,版本生成请使用深度生成(/deep/generate)");
        }
        try {
            return R.ok(versionService.generate(projectId, body.get("styleIds")));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(409, ex.getMessage());
        } catch (NotReadyException ex) {
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "仿写生成失败: " + ex.getMessage());
        }
    }

    /** 列出项目全部版本。 */
    @GetMapping("/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<ArticleVersionEntity>> listVersions(@PathVariable Long projectId) {
        return R.ok(versionService.list(projectId));
    }

    /** 设定当前版本（用于后续预览/发布）。 */
    @PutMapping("/current-version")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setCurrentVersion(@PathVariable Long projectId, @RequestParam Long versionId) {
        try {
            versionService.setCurrent(projectId, versionId);
            return R.ok();
        } catch (Exception ex) {
            return R.fail(400, ex.getMessage());
        }
    }

    /** 保存版本正文（S4 预览页左栏编辑;ADMIN/EDITOR）。 */
    @PutMapping("/versions/{versionId}/content")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> updateVersionContent(@PathVariable Long projectId, @PathVariable Long versionId,
                                        @RequestBody java.util.Map<String, String> body) {
        try {
            versionService.updateContent(projectId, versionId, body.get("contentMd"));
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存失败: " + ex.getMessage());
        }
    }

    /** 编辑版本标题（S6;ADMIN/EDITOR）。body: {"title":"..."}。 */
    @PutMapping("/versions/{versionId}/title")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> updateVersionTitle(@PathVariable Long projectId, @PathVariable Long versionId,
                                       @RequestBody java.util.Map<String, String> body) {
        try {
            versionService.updateTitle(projectId, versionId, body.get("title"));
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存失败: " + ex.getMessage());
        }
    }

    /** 简报阶段点选标题（S6;ADMIN/EDITOR）。body: {"title":"..."}，空串清除。 */
    @PutMapping("/selected-title")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setSelectedTitle(@PathVariable Long projectId, @RequestBody java.util.Map<String, String> body) {
        ArticleProjectEntity e = mapper.selectById(projectId);
        if (e == null) return R.fail(404, "项目不存在");
        String title = body.get("title");
        if (title != null && title.length() > 200) return R.fail(400, "标题不能超过 200 字");
        // 单列显式 set:避免 updateById 全字段覆盖并发写入的状态列
        mapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("selected_title", title == null || title.isBlank() ? null : title)
                .set("updated_at", LocalDateTime.now()));
        return R.ok();
    }
}
