package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.car.service.CarModelMatcherService;
import com.sparkora.common.R;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.dto.ProjectRequest;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.ArticleProjectCarService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创作项目 CRUD（S1 起接真实 AI）。
 *
 * 09-27-split-monoliths：按子域拆分为多个薄控制器后，本类只承载 Project CRUD。
 * 其余子域：{@link ProjectBriefController}、{@link ProjectVersionController}、
 * {@link ProjectImageController}、{@link ProjectPreviewController}、{@link ProjectPublishController}。
 */
@RestController
@RequestMapping("/api/projects")
public class ArticleProjectController {

    /** 10-19:项目渠道。公众号沿用现有四步向导,王朝小程序走三步工作台。 */
    private static final String CHANNEL_GZH = "gzh";
    private static final String CHANNEL_BYD_MINIAPP = "byd_miniapp";

    private final ArticleProjectMapper mapper;
    private final ArticleVersionMapper versionMapper;
    private final ArticleProjectCarService carService;
    private final CarModelMatcherService matcherService;

    public ArticleProjectController(ArticleProjectMapper mapper,
                                    ArticleVersionMapper versionMapper,
                                    ArticleProjectCarService carService,
                                    CarModelMatcherService matcherService) {
        this.mapper = mapper;
        this.versionMapper = versionMapper;
        this.carService = carService;
        this.matcherService = matcherService;
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
        // 10-19:渠道缺省 gzh(存量/旧客户端行为不变);非法渠道显式 400。
        String channel = normalizeChannel(req.getChannel());
        if (channel == null) {
            return R.fail(400, "渠道仅支持 gzh(公众号)或 byd_miniapp(王朝小程序)");
        }
        boolean byd = CHANNEL_BYD_MINIAPP.equals(channel);
        // 公众号沿用 topic 必填(原 @NotBlank 语义);小程序允许空主题,从空草稿开始自由编辑。
        if (!byd && (req.getTopic() == null || req.getTopic().isBlank())) {
            return R.fail(400, "主题不能为空");
        }
        // 文章仿写(09-09-article-imitation)仅公众号渠道适用;小程序渠道忽略该组参数,固定按主题创作落库。
        String genSource = "TOPIC";
        if (!byd) {
            genSource = req.getGenSource() == null || req.getGenSource().isBlank() ? "TOPIC" : req.getGenSource();
            if (!"TOPIC".equals(genSource) && !"IMITATION".equals(genSource)) {
                return R.fail(400, "创作方式仅支持 TOPIC(主题创作)或 IMITATION(文章仿写)");
            }
            if ("IMITATION".equals(genSource)
                    && (req.getImitationText() == null || req.getImitationText().isBlank())) {
                return R.fail(400, "文章仿写必须粘贴参考原文");
            }
        }
        CurrentUser cu = SecurityUtil.require();
        LocalDateTime now = LocalDateTime.now();
        ArticleProjectEntity e = new ArticleProjectEntity();
        // topic 列 NOT NULL:小程序空主题落空串,不落 null。
        e.setTopic(byd ? blankToEmpty(req.getTopic()) : req.getTopic());
        e.setChannel(channel);
        e.setAudience(req.getAudience());
        e.setWordCountTarget(req.getWordCountTarget());
        e.setBrandVoiceProfileId(req.getBrandVoiceProfileId());
        e.setContentDescription(req.getContentDescription());
        e.setSelectedTitle(req.getSelectedTitle());
        e.setGenSource(genSource);
        e.setImitationText("IMITATION".equals(genSource) ? req.getImitationText() : null);
        e.setStatus("DRAFT");
        e.setCreatedBy(cu.getUsername());
        e.setDeleted(0);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        mapper.insert(e);

        if (byd) {
            // 方式一(自由编辑器)从空草稿版本直接开始:创建即建 version 行并指向 current_version_id。
            // 不走 ProjectStatusService(非状态机转换,与项目 insert 同属创建期初始化)。
            ArticleVersionEntity v = new ArticleVersionEntity();
            v.setProjectId(e.getId());
            v.setTitle("");
            v.setContentMd("");
            v.setWordCount(0);
            v.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(v);
            mapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", e.getId())
                    .set("current_version_id", v.getId())
                    .set("updated_at", LocalDateTime.now()));
            e.setCurrentVersionId(v.getId());
            return R.ok(e.getId());
        }

        // S6 多车型:用户已选则直接写入;未选则 AI 自动识别是否应关联车型并回填
        // 仿写模式不关联车型(任意题材原文与车型库强行匹配会注入无关数据约束)
        List<Long> modelIds = req.getCarModelIds();
        if (!"IMITATION".equals(genSource)) {
            if (modelIds == null || modelIds.isEmpty()) {
                CarModelMatcherService.MatchResult m = matcherService.match(req.getTopic(), req.getContentDescription());
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
        boolean byd = CHANNEL_BYD_MINIAPP.equals(e.getChannel());
        // 公众号渠道保持 topic 必填(原 @NotBlank 语义);小程序允许空主题。
        if (!byd && (req.getTopic() == null || req.getTopic().isBlank())) {
            return R.fail(400, "主题不能为空");
        }
        // 白名单列显式 set:绝不触碰 status/current_*/publish_*/last_*_error 等服务端状态列。
        // updateById 全字段回写会用旧快照把并发推进的状态(生成中/已发布)覆盖回去。
        UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<>();
        uw.eq("id", id)
                .set("topic", byd ? blankToEmpty(req.getTopic()) : req.getTopic())
                .set("audience", req.getAudience())
                .set("word_count_target", req.getWordCountTarget())
                .set("brand_voice_profile_id", req.getBrandVoiceProfileId())
                .set("content_description", req.getContentDescription())
                .set("selected_title", req.getSelectedTitle())
                .set("updated_at", LocalDateTime.now());
        mapper.update(null, uw);
        // S6 多车型:覆盖式写入关联车型
        carService.replace(id, req.getCarModelIds());
        return R.ok();
    }

    /** null/空串按 gzh;仅 gzh / byd_miniapp 合法,其余返回 null 由调用方 400。 */
    private static String normalizeChannel(String raw) {
        if (raw == null || raw.isBlank()) return CHANNEL_GZH;
        String c = raw.trim();
        return CHANNEL_GZH.equals(c) || CHANNEL_BYD_MINIAPP.equals(c) ? c : null;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
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
}
