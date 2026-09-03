package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S5 发布服务(方案 A):预览与发布同渲染核,preview HTML = 发布真值。
 *
 * 链路(同步,一次调用完成):
 *  1. previewService.preview(...) —— 项目状态校验 + 取图床 URL + wenyan render(与预览完全同参同源);
 *  2. 校验渲染未降级(降级 HTML 不进公众号);
 *  3. 组 gzhContent JSON(title + content=渲染 HTML)→ wenyan-server /upload → /publish(fileId)
 *     → {media_id}(上传后立即发布,远低于 server 端 10 分钟 TTL);
 *  4. 原子落库:status=PUBLISHED_DRAFT + publish_media_id/publish_theme/published_at,清 last_publish_error。
 *
 * 失败语义:任何一步失败抛异常(中文原因),控制器捕获后调 markFailure 写 last_publish_error;
 * 状态推进只发生在全部成功之后,失败状态原样保留,可重试(可重发覆盖草稿)。
 */
@Slf4j
@Service
public class PublishService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final PreviewService previewService;
    private final WenyanServerService serverService;
    private final ObjectMapper json;

    public PublishService(ArticleProjectMapper projectMapper, ArticleVersionMapper versionMapper,
                          PreviewService previewService, WenyanServerService serverService,
                          ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.previewService = previewService;
        this.serverService = serverService;
        this.json = json;
    }

    /** 发布(重发)到公众号草稿箱。参数与预览一致(theme/highlight/macStyle/footnote)。 */
    public java.util.Map<String, Object> publish(Long projectId, String theme, String highlight,
                                                 Boolean macStyle, Boolean footnote) {
        // 0) 发布通道配置检查(未配置直接中文报错,不触碰状态)
        serverService.requireConfigured();

        // 1) 同源渲染:内部做项目存在性/状态(VERSIONS_READY|PUBLISHED_DRAFT)/当前版本/参数白名单校验
        //    并完成封面+插图的图床 URL 组装(preview 与发布同一份组装,保证所见即所得)
        java.util.Map<String, Object> rendered = previewService.preview(projectId, theme, highlight, macStyle, footnote);
        if (Boolean.TRUE.equals(rendered.get("degraded"))) {
            Object reason = rendered.get("degradedReason");
            throw new IllegalStateException("排版渲染已降级,为保证公众号排版与预览一致,已中止发布"
                    + (reason == null ? "" : "(原因: " + reason + ")。请检查 wenyan CLI 配置后重试"));
        }
        String html = (String) rendered.get("html");
        String usedTheme = (String) rendered.get("theme");
        if (html == null || html.isBlank()) throw new IllegalStateException("渲染结果为空,已中止发布");

        // 1.5) 微信要求文章至少要有封面图:当前版本未设封面则拒绝发布(避免发布无封面文章)
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        ArticleVersionEntity v = versionMapper.selectById(p.getCurrentVersionId());
        if (v == null) throw new IllegalStateException("当前版本不存在，请重新选定");
        if (v.getCoverImageId() == null)
            throw new IllegalStateException("公众号要求文章至少要有封面图，请先在「预览」步骤为当前版本设置封面后再发布");

        // 2) 组 gzhContent(publish JSON 文件内容):title 必填、content=渲染 HTML
        //    微信草稿接口 title 上限 64 字符,超长截断防 400
        String title = v.getTitle() == null || v.getTitle().isBlank() ? "无标题" : v.getTitle();
        if (title.length() > 64) title = title.substring(0, 64);
        java.util.Map<String, Object> gzh = new LinkedHashMap<>();
        gzh.put("title", title);
        gzh.put("content", html);

        // 3) upload(json)→ publish(fileId) → media_id(同步链,TTL 10 分钟充裕)
        String gzhJson;
        try {
            gzhJson = json.writeValueAsString(gzh);
        } catch (Exception e) {
            throw new IllegalStateException("发布内容序列化失败: " + e.getMessage(), e);
        }
        String fileId = serverService.uploadJson("sparkora-publish-" + projectId + ".json", gzhJson);
        log.info("发布内容已上传 wenyan-server fileId={} ({} KB)", fileId, gzhJson.length() / 1024);
        String mediaId = serverService.publish(fileId);

        // 4) 原子落库:PUBLISHED_DRAFT(可重发) + media_id/主题/时间,清错误
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("status", "PUBLISHED_DRAFT")
                .set("publish_media_id", mediaId)
                .set("publish_theme", usedTheme)
                .set("published_at", now)
                .set("last_publish_error", null)
                .set("updated_at", now));
        log.info("项目 {} 已发布到公众号草稿箱 media_id={} theme={}", projectId, mediaId, usedTheme);

        java.util.Map<String, Object> result = new LinkedHashMap<>();
        result.put("mediaId", mediaId);
        result.put("theme", usedTheme);
        result.put("publishedAt", now);
        return result;
    }

    /** 发布失败:写 last_publish_error(不动状态,可重试)。 */
    public void markFailure(Long projectId, String message) {
        String msg = message == null ? "未知错误" : message.replaceAll("\\s+", " ").trim();
        if (msg.length() > 990) msg = msg.substring(0, 990) + "…";
        try {
            projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .set("last_publish_error", msg)
                    .set("updated_at", java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("发布失败原因落库失败 project={}: {}", projectId, e.getMessage());
        }
    }
}