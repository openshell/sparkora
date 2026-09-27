package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PreviewStyleRequest;
import com.sparkora.domain.dto.PublishMetaRequest;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 项目预览与预览到发布衔接（样式/元信息/预览渲染/发布参数）子域接口。
 *
 * 09-27-split-monoliths：从 {@link ArticleProjectController} 按子域拆出，方法体逐字搬迁（零行为变化）。
 * 字段级契约见 docs/spec/preview.md、docs/spec/publish.md。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectPreviewController {

    private final ArticleProjectMapper mapper;
    private final com.sparkora.service.PreviewService previewService;

    public ProjectPreviewController(ArticleProjectMapper mapper,
                                    com.sparkora.service.PreviewService previewService) {
        this.mapper = mapper;
        this.previewService = previewService;
    }

    // ==================== 预览到发布衔接(09-11-preview-publish-bridge)====================

    /** 保存预览页样式(主题/高亮/Mac/脚注,项目级;ADMIN/EDITOR)。只更新非 null 字段。 */
    @PutMapping("/preview-style")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> savePreviewStyle(@PathVariable Long projectId, @RequestBody PreviewStyleRequest req) {
        ArticleProjectEntity e = mapper.selectById(projectId);
        if (e == null) return R.fail(404, "项目不存在");
        try {
            String theme = previewService.requireTheme(req.getTheme());
            String highlight = previewService.requireHighlight(req.getHighlight());
            // 用 UpdateWrapper 显式 set 仅目标列:避免 updateById 全字段覆盖把并发写入(如生成中状态)回写旧值
            UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<>();
            uw.eq("id", projectId);
            if (theme != null) uw.set("preview_theme", theme);
            if (highlight != null) uw.set("preview_highlight", highlight);
            if (req.getMacStyle() != null) uw.set("preview_mac_style", req.getMacStyle());
            if (req.getFootnote() != null) uw.set("preview_footnote", req.getFootnote());
            uw.set("updated_at", LocalDateTime.now());
            mapper.update(null, uw);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
    }

    /** 保存发布元信息(作者/原文地址,项目级;ADMIN/EDITOR)。只更新请求中出现的字段,允许空串清空。 */
    @PutMapping("/publish-meta")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> savePublishMeta(@PathVariable Long projectId, @Valid @RequestBody PublishMetaRequest req) {
        ArticleProjectEntity e = mapper.selectById(projectId);
        if (e == null) return R.fail(404, "项目不存在");
        // 用 UpdateWrapper 显式 set:空串需落 NULL 清空,updateById 的 NOT_NULL 策略会跳过 null 字段
        UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<>();
        uw.eq("id", projectId);
        if (req.getAuthor() != null) uw.set("author", req.getAuthor().isBlank() ? null : req.getAuthor().trim());
        if (req.getSourceUrl() != null) uw.set("source_url", req.getSourceUrl().isBlank() ? null : req.getSourceUrl().trim());
        uw.set("updated_at", LocalDateTime.now());
        mapper.update(null, uw);
        return R.ok();
    }

    // ==================== 预览（S4，方案 A:wenyan 同核渲染）====================

    /** 预览(三角色;主题等白名单校验在 service)。显式 @PreAuthorize 与既有矩阵对齐。 */
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> preview(@PathVariable Long projectId,
                                                    @RequestParam(required = false) String theme,
                                                    @RequestParam(required = false) String highlight,
                                                    @RequestParam(required = false) Boolean macStyle,
                                                    @RequestParam(required = false) Boolean footnote) {
        try {
            return R.ok(previewService.preview(projectId, theme, highlight, macStyle, footnote));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "预览失败: " + ex.getMessage());
        }
    }

    // ==================== 发布参数（S5,公众号草稿箱;发布通道=wenyan-server）====================

    /** 发布参数与配置状态(三角色可读;viewer 只读)。 */
    @GetMapping("/publish-options")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> publishOptions(@PathVariable Long projectId) {
        ArticleProjectEntity p = mapper.selectById(projectId);
        if (p == null) return R.fail(404, "项目不存在");
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("themes", previewService.themeOptions());
        m.put("highlights", java.util.List.of("solarized-light", "monokai", "github", "dracula"));
        m.put("defaultTheme", previewService.defaultTheme());
        m.put("highlight", previewService.defaultHighlight());
        m.put("macStyle", previewService.defaultMacStyle());
        m.put("footnote", previewService.defaultFootnote());
        // 09-11-preview-publish-bridge:发布页据 preview* 初始化样式(优先于全局默认),据 author/sourceUrl 初始化手填项
        m.put("previewTheme", p.getPreviewTheme());
        m.put("previewHighlight", p.getPreviewHighlight());
        m.put("previewMacStyle", p.getPreviewMacStyle());
        m.put("previewFootnote", p.getPreviewFootnote());
        m.put("author", p.getAuthor());
        m.put("sourceUrl", p.getSourceUrl());
        // 发布通道就绪度:server 配置齐备与否 + 可达/鉴权探针(懒探测,失败不阻塞页面)
        boolean configOk = previewService.serverConfigured();
        boolean channelOk = configOk && previewService.serverVerify();
        m.put("publishEnabled", channelOk);
        m.put("publishConfigOk", configOk);
        if (!configOk) m.put("publishDisabledReason", "发布通道未配置(WENYAN_MCP_SERVER_URL / WENYAN_MCP_SERVER_API_KEY)");
        else if (!channelOk) m.put("publishDisabledReason", "发布通道不可用(API Key 无效或 server 不可达)");
        m.put("wenyanServer", previewService.serverHealth());
        // 已发布信息(重发场景展示)
        m.put("publishMediaId", p.getPublishMediaId());
        m.put("publishTheme", p.getPublishTheme());
        m.put("publishedAt", p.getPublishedAt());
        m.put("lastPublishError", p.getLastPublishError());
        return R.ok(m);
    }
}
