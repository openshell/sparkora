package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.ImageGenDTO;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.ImageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 配图接口（S3b，字段级契约见 docs/s0-spec.md §10）。
 * - VIEWER 可读图库；ADMIN/EDITOR 可上传/生成/选定封面插图。
 * - AI 生成接口耗时较长，前端单独放宽超时（同 generate/versions 模式）。
 * - body 里的 projectId/refImageId 做健壮解析：前端可能传字符串(路由参数)或数字，均接受。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService service;
    private final com.sparkora.config.WenyanProperties wenyanProps;
    private final com.sparkora.service.PreviewService previewService;

    public ImageController(ImageService service, com.sparkora.config.WenyanProperties wenyanProps,
                           com.sparkora.service.PreviewService previewService) {
        this.service = service;
        this.wenyanProps = wenyanProps;
        this.previewService = previewService;
    }

    /** 数字字段健壮解析：兼容 Number(Integer/Long/…) 与字符串形式（"4"/" 4"），空/非法返回 null 或抛 400。 */
    private static Long toLong(Object v, String field) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
    }

    /** 图库分页列表（S10）：?projectId=&source=&keyword=&page=&size= 组合查询，响应 PageResult。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<ImageAssetEntity>> list(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) String source,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "24") long size) {
        try {
            return R.ok(service.list(projectId, source, keyword, page, size));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
    }

    /** 上传图库图（projectId 可选=全局图库）：multipart file。类型 png/jpg/webp，≤ IMAGE_MAX_UPLOAD_MB。 */
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ImageAssetEntity> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long projectId) {
        try {
            CurrentUser cu = SecurityUtil.require();
            return R.ok(service.upload(projectId, file, cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (org.springframework.web.multipart.MultipartException ex) {
            Throwable root = ex.getRootCause() != null ? ex.getRootCause() : ex;
            return R.fail(400, "上传失败: " + root.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "上传失败: " + ex.getMessage());
        }
    }

    /** 删除图库图（ADMIN/EDITOR；被封面/插图引用时 400 并提示引用方）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "删除失败: " + ex.getMessage());
        }
    }

    /** 文生图：body {projectId?, prompt, size?, n?}。S10：@Valid DTO + 响应改候选列表（n 张逐张入库）。 */
    @PostMapping("/generate-text")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ImageAssetEntity>> generateText(@Validated @RequestBody ImageGenDTO body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = toLong(body.getProjectId(), "projectId");
            return R.ok(service.generateText2Image(projectId, body.getPrompt(), body.getSize(), body.getN(), cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 图生图：body {projectId?, refImageId, prompt, size?, n?}。S10：@Valid DTO + 响应改候选列表。 */
    @PostMapping("/generate-from-image")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ImageAssetEntity>> generateFromImage(@Validated @RequestBody ImageGenDTO body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = toLong(body.getProjectId(), "projectId");
            Long refImageId = toLong(body.getRefImageId(), "refImageId");
            if (refImageId == null) return R.fail(400, "缺少 refImageId");
            return R.ok(service.generateImage2Image(projectId, refImageId, body.getPrompt(), body.getSize(), body.getN(), cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 重新生成（S10）：同源图 prompt/gen_size 产新图（不覆盖源图）。 */
    @PostMapping("/{id}/regenerate")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ImageAssetEntity>> regenerate(@PathVariable Long id) {
        try {
            CurrentUser cu = SecurityUtil.require();
            return R.ok(service.regenerate(id, cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 预览参数清单(S4):主题目录/高亮清单与开关默认值,前端下拉同源。 */
    @GetMapping("/preview-options")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> previewOptions() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("themes", previewService.themeOptions());
        m.put("highlights", java.util.List.of("solarized-light", "monokai", "github", "dracula"));
        m.put("defaultTheme", wenyanProps.getDefaultTheme());
        m.put("highlight", wenyanProps.getHighlight());
        m.put("macStyle", wenyanProps.isMacStyle());
        m.put("footnote", wenyanProps.isFootnote());
        return R.ok(m);
    }
}