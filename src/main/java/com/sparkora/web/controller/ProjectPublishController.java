package com.sparkora.web.controller;

import com.sparkora.common.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 项目发布到公众号草稿箱（S5）子域接口。
 *
 * 09-27-split-monoliths：从 {@link ArticleProjectController} 按子域拆出，方法体逐字搬迁（零行为变化）。
 * 字段级契约见 docs/spec/publish.md。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectPublishController {

    private final com.sparkora.service.PublishService publishService;

    public ProjectPublishController(com.sparkora.service.PublishService publishService) {
        this.publishService = publishService;
    }

    // ==================== 发布（S5,公众号草稿箱;发布通道=wenyan-server）====================

    /** 发布到公众号草稿箱(ADMIN/EDITOR)。参数与预览一致;成功推进 PUBLISHED_DRAFT,可重发覆盖。 */
    @PostMapping("/publish")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<java.util.Map<String, Object>> publish(@PathVariable Long projectId,
                                                    @RequestParam(required = false) String theme,
                                                    @RequestParam(required = false) String highlight,
                                                    @RequestParam(required = false) Boolean macStyle,
                                                    @RequestParam(required = false) Boolean footnote) {
        try {
            return R.ok(publishService.publish(projectId, theme, highlight, macStyle, footnote));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 前置不满足(状态/通道未配置/渲参非法)或通道错误 → 客户端错误语义
            publishService.markFailure(projectId, ex.getMessage());
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            publishService.markFailure(projectId, ex.getMessage());
            return R.fail(500, "发布失败: " + ex.getMessage());
        }
    }
}
