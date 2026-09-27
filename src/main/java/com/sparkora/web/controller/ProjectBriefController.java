package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.service.BriefService;
import com.sparkora.service.ImitationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 项目简报（brief）与文章仿写（imitation）子域接口。
 *
 * 09-27-split-monoliths：从 {@link ArticleProjectController} 按子域拆出，方法体逐字搬迁（零行为变化）。
 * 子域路径前缀 `/api/projects/{projectId}`（对齐 {@link DeepController} 先例）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectBriefController {

    private final BriefService briefService;
    private final ImitationService imitationService;

    public ProjectBriefController(BriefService briefService, ImitationService imitationService) {
        this.briefService = briefService;
        this.imitationService = imitationService;
    }

    /**
     * 生成 brief（S1：接真实 AI）。同步调用，前端 loading 等待。
     * 状态机 DRAFT→GENERATING_BRIEF→READY；失败回 DRAFT 并写 lastBriefError（可在 project 详情查看）。
     * 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):快速模式入口封死,
     * 所有生成必走深度流程(POST /api/deep/{id}/clarify);存量 FAST 项目产物可读,重新生成走深度。
     */
    @PostMapping("/generate/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ArticleBriefEntity> generateBrief(@PathVariable Long projectId) {
        return R.fail(410, "生成流程已升级为深度模式,请使用深度生成(/deep/clarify)");
    }

    /**
     * 取项目当前 brief（无则 data=null）。
     */
    @GetMapping("/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<ArticleBriefEntity> currentBrief(@PathVariable Long projectId) {
        return R.ok(briefService.currentBrief(projectId));
    }

    // ==================== 文章仿写（09-09-article-imitation，字段级契约见 docs/spec/imitation.md）====================

    /**
     * 分析原文 + 风格推荐(ADMIN/EDITOR)。09-27-gen-async 异步化:同步毫秒级返回占位标记,
     * 后台 @Async 执行 AI 分析;前端靠项目状态轮询(GENERATING_BRIEF→READY)翻转刷新。
     * 状态机 DRAFT/READY→GENERATING_BRIEF→READY;失败回 DRAFT 写 lastBriefError;生成中重触发 409。
     */
    @PostMapping("/imitation/analyze")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<java.util.Map<String, Object>> analyzeImitation(@PathVariable Long projectId) {
        try {
            return R.ok(imitationService.analyze(projectId));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "原文分析失败: " + ex.getMessage());
        }
    }

    /** 取仿写分析+风格推荐(三角色可读;无则 data=null)。 */
    @GetMapping("/imitation")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> imitationAnalysis(@PathVariable Long projectId) {
        return R.ok(imitationService.currentAnalysis(projectId));
    }
}
