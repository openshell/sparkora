package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.car.service.CarModelMatcherService;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.ProjectRequest;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.ArticleProjectCarService;
import com.sparkora.service.BriefService;
import com.sparkora.service.ImitationService;
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
    private final ArticleProjectCarService carService;
    private final CarModelMatcherService matcherService;
    private final com.sparkora.service.ImageService imageService;
    private final com.sparkora.service.PreviewService previewService;
    private final com.sparkora.service.PublishService publishService;
    private final ImitationService imitationService;

    public ArticleProjectController(ArticleProjectMapper mapper, BriefService briefService, VersionService versionService,
                                    ArticleProjectCarService carService, CarModelMatcherService matcherService,
                                    com.sparkora.service.ImageService imageService,
                                    com.sparkora.service.PreviewService previewService,
                                    com.sparkora.service.PublishService publishService,
                                    ImitationService imitationService) {
        this.mapper = mapper;
        this.briefService = briefService;
        this.versionService = versionService;
        this.carService = carService;
        this.matcherService = matcherService;
        this.imageService = imageService;
        this.previewService = previewService;
        this.publishService = publishService;
        this.imitationService = imitationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<ArticleProjectEntity>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false) String orderDir) {

        QueryWrapper<ArticleProjectEntity> qw = new QueryWrapper<>();
        if (topic != null && !topic.isBlank()) qw.like("topic", topic);
        if (status != null && !status.isBlank()) qw.eq("status", status);
        // 排序白名单:仅允许映射到固定列名,非法值静默回退默认(updated_at desc),
        // 原始参数字符串绝不透传 QueryWrapper,避免 SQL 注入面。
        String col = "createdAt".equals(orderBy) ? "created_at" : "updated_at";
        boolean asc = "asc".equalsIgnoreCase(orderDir);
        if (asc) qw.orderByAsc(col); else qw.orderByDesc(col);

        Page<ArticleProjectEntity> p = new Page<>(page, size);
        Page<ArticleProjectEntity> result = mapper.selectPage(p, qw);
        return R.ok(new PageResult<>(result.getRecords(), result.getTotal(), page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<ArticleProjectEntity> get(@PathVariable Long id) {
        ArticleProjectEntity e = mapper.selectById(id);
        if (e == null) return R.fail(404, "项目不存在");
        e.setCarModelIds(carService.listModelIds(id));
        return R.ok(e);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Long> create(@Valid @RequestBody ProjectRequest req) {
        // 文章仿写(09-09-article-imitation):genSource 缺省按主题创作;IMITATION 时原文必填非空
        String genSource = req.getGenSource() == null || req.getGenSource().isBlank() ? "TOPIC" : req.getGenSource();
        if (!"TOPIC".equals(genSource) && !"IMITATION".equals(genSource)) {
            return R.fail(400, "创作方式仅支持 TOPIC(主题创作)或 IMITATION(文章仿写)");
        }
        if ("IMITATION".equals(genSource)
                && (req.getImitationText() == null || req.getImitationText().isBlank())) {
            return R.fail(400, "文章仿写必须粘贴参考原文");
        }
        CurrentUser cu = SecurityUtil.require();
        ArticleProjectEntity e = new ArticleProjectEntity();
        e.setTopic(req.getTopic());
        e.setKeywords(req.getKeywords());
        e.setAudience(req.getAudience());
        e.setWordCountTarget(req.getWordCountTarget());
        e.setBrandVoiceProfileId(req.getBrandVoiceProfileId());
        e.setExtraInfo(req.getExtraInfo());
        e.setSelectedTitle(req.getSelectedTitle());
        e.setRemark(req.getRemark());
        e.setGenSource(genSource);
        e.setImitationText("IMITATION".equals(genSource) ? req.getImitationText() : null);
        e.setStatus("DRAFT");
        e.setCreatedBy(cu.getUsername());
        e.setDeleted(0);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        mapper.insert(e);

        // S6 多车型:用户已选则直接写入;未选则 AI 自动识别是否应关联车型并回填
        // 仿写模式不关联车型(任意题材原文与车型库强行匹配会注入无关数据约束)
        List<Long> modelIds = req.getCarModelIds();
        if (!"IMITATION".equals(genSource)) {
            if (modelIds == null || modelIds.isEmpty()) {
                CarModelMatcherService.MatchResult m = matcherService.match(req.getTopic(), req.getKeywords());
                if (m.related()) modelIds = m.modelIds();
            }
            carService.replace(e.getId(), modelIds);
        }
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
        e.setExtraInfo(req.getExtraInfo());
        e.setSelectedTitle(req.getSelectedTitle());
        e.setRemark(req.getRemark());
        e.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(e);
        // S6 多车型:覆盖式写入关联车型
        carService.replace(id, req.getCarModelIds());
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
     * 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):快速模式入口封死,
     * 所有生成必走深度流程(POST /api/deep/{id}/clarify);存量 FAST 项目产物可读,重新生成走深度。
     */
    @PostMapping("/{id}/generate/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ArticleBriefEntity> generateBrief(@PathVariable Long id) {
        return R.fail(410, "生成流程已升级为深度模式,请使用深度生成(/deep/clarify)");
    }

    /**
     * 取项目当前 brief（无则 data=null）。
     */
    @GetMapping("/{id}/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<ArticleBriefEntity> currentBrief(@PathVariable Long id) {
        return R.ok(briefService.currentBrief(id));
    }

    // ==================== 文章仿写（09-09-article-imitation，字段级契约见 spec §14）====================

    /**
     * 分析原文 + 风格推荐(ADMIN/EDITOR)。同步调用,前端 loading 等待(AI 耗时较长,前端单独放宽超时)。
     * 状态机 DRAFT/READY→GENERATING_BRIEF→READY;失败回 DRAFT 写 lastBriefError;生成中重触发 409。
     */
    @PostMapping("/{id}/imitation/analyze")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ArticleBriefEntity> analyzeImitation(@PathVariable Long id) {
        try {
            return R.ok(imitationService.analyze(id));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(409, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "原文分析失败: " + ex.getMessage());
        }
    }

    /** 取仿写分析+风格推荐(三角色可读;无则 data=null)。 */
    @GetMapping("/{id}/imitation")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> imitationAnalysis(@PathVariable Long id) {
        return R.ok(imitationService.currentAnalysis(id));
    }

    // ==================== 文章版本（S1b）====================

    /**
     * 生成多版本正文（基于当前 brief + 用户选择的风格）。body: {"styleIds":[1,2]}（风格库 id 列表）。
     * 每选一个风格生成一版。同步调用，前端 loading 等待（AI 耗时较长，前端单独放宽超时）。
     * 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):主题创作项目封死(深度版本走 POST /api/deep/{id}/generate);
     * 文章仿写(09-09-article-imitation §14)例外:genSource=IMITATION 时本接口复用为仿写生成
     * (多风格一次生成,产出仿写正文+相似度自检,状态机同 §4)。
     */
    @PostMapping("/{id}/generate/versions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ArticleVersionEntity>> generateVersions(@PathVariable Long id,
                                                          @RequestBody java.util.Map<String, java.util.List<Long>> body) {
        // 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):主题创作项目恒 410(深度单版走 /deep/generate);
        // 文章仿写(09-09-article-imitation §14)例外放行:复用本接口多风格一次生成(仿写 prompt+去图+相似度自检)。
        ArticleProjectEntity p = mapper.selectById(id);
        if (p == null) return R.fail(404, "项目不存在");
        if (!"IMITATION".equals(p.getGenSource())) {
            return R.fail(410, "生成流程已升级为深度模式,版本生成请使用深度生成(/deep/generate)");
        }
        try {
            return R.ok(versionService.generate(id, body.get("styleIds")));
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

    /** 保存版本正文（S4 预览页左栏编辑;ADMIN/EDITOR）。 */
    @PutMapping("/{id}/versions/{versionId}/content")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> updateVersionContent(@PathVariable Long id, @PathVariable Long versionId,
                                        @RequestBody java.util.Map<String, String> body) {
        try {
            versionService.updateContent(id, versionId, body.get("contentMd"));
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存失败: " + ex.getMessage());
        }
    }

    /** 编辑版本标题（S6;ADMIN/EDITOR）。body: {"title":"..."}。 */
    @PutMapping("/{id}/versions/{versionId}/title")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> updateVersionTitle(@PathVariable Long id, @PathVariable Long versionId,
                                       @RequestBody java.util.Map<String, String> body) {
        try {
            versionService.updateTitle(id, versionId, body.get("title"));
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存失败: " + ex.getMessage());
        }
    }

    /** 简报阶段点选标题（S6;ADMIN/EDITOR）。body: {"title":"..."}，空串清除。 */
    @PutMapping("/{id}/selected-title")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setSelectedTitle(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        ArticleProjectEntity e = mapper.selectById(id);
        if (e == null) return R.fail(404, "项目不存在");
        String title = body.get("title");
        if (title != null && title.length() > 200) return R.fail(400, "标题不能超过 200 字");
        e.setSelectedTitle(title == null || title.isBlank() ? null : title);
        e.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(e);
        return R.ok();
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

    // ==================== 预览（S4，方案 A:wenyan 同核渲染）====================

    /** 预览(三角色;主题等白名单校验在 service)。显式 @PreAuthorize 与既有矩阵对齐。 */
    @PostMapping("/{id}/preview")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> preview(@PathVariable Long id,
                                                    @RequestParam(required = false) String theme,
                                                    @RequestParam(required = false) String highlight,
                                                    @RequestParam(required = false) Boolean macStyle,
                                                    @RequestParam(required = false) Boolean footnote) {
        try {
            return R.ok(previewService.preview(id, theme, highlight, macStyle, footnote));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "预览失败: " + ex.getMessage());
        }
    }

    // ==================== 发布（S5,公众号草稿箱;发布通道=wenyan-server）====================

    /** 发布参数与配置状态(三角色可读;viewer 只读)。 */
    @GetMapping("/{id}/publish-options")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> publishOptions(@PathVariable Long id) {
        ArticleProjectEntity p = mapper.selectById(id);
        if (p == null) return R.fail(404, "项目不存在");
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("themes", previewService.themeOptions());
        m.put("highlights", java.util.List.of("solarized-light", "monokai", "github", "dracula"));
        m.put("defaultTheme", previewService.defaultTheme());
        m.put("highlight", previewService.defaultHighlight());
        m.put("macStyle", previewService.defaultMacStyle());
        m.put("footnote", previewService.defaultFootnote());
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

    /** 发布到公众号草稿箱(ADMIN/EDITOR)。参数与预览一致;成功推进 PUBLISHED_DRAFT,可重发覆盖。 */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<java.util.Map<String, Object>> publish(@PathVariable Long id,
                                                    @RequestParam(required = false) String theme,
                                                    @RequestParam(required = false) String highlight,
                                                    @RequestParam(required = false) Boolean macStyle,
                                                    @RequestParam(required = false) Boolean footnote) {
        try {
            return R.ok(publishService.publish(id, theme, highlight, macStyle, footnote));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 前置不满足(状态/通道未配置/渲参非法)或通道错误 → 客户端错误语义
            publishService.markFailure(id, ex.getMessage());
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            publishService.markFailure(id, ex.getMessage());
            return R.fail(500, "发布失败: " + ex.getMessage());
        }
    }
}
