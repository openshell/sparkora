package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.ImageService;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public ImageController(ImageService service, com.sparkora.config.WenyanProperties wenyanProps) {
        this.service = service;
        this.wenyanProps = wenyanProps;
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

    /** 图库列表（projectId 可选过滤）。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<ImageAssetEntity>> list(@RequestParam(required = false) Long projectId) {
        return R.ok(service.list(projectId));
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

    /** 文生图：body {projectId, prompt, size?}。projectId 兼容字符串/数字。 */
    @PostMapping("/generate-text")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ImageAssetEntity> generateText(@RequestBody Map<String, Object> body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = requireProjectId(body);
            String prompt = (String) body.get("prompt");
            String size = (String) body.get("size");
            return R.ok(service.generateText2Image(projectId, prompt, size, cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 图生图：body {projectId, refImageId, prompt, size?}。数字字段兼容字符串/数字。 */
    @PostMapping("/generate-from-image")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ImageAssetEntity> generateFromImage(@RequestBody Map<String, Object> body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = requireProjectId(body);
            Long refImageId = toLong(body.get("refImageId"), "refImageId");
            String prompt = (String) body.get("prompt");
            String size = (String) body.get("size");
            return R.ok(service.generateImage2Image(projectId, refImageId, prompt, size, cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 预览参数清单(S4):主题/高亮清单与开关默认值,读 .env(WENYAN_*),前端下拉同源。 */
    @GetMapping("/preview-options")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> previewOptions() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("themes", wenyanProps.themeNameList());
        m.put("highlights", java.util.List.of("solarized-light", "monokai", "github", "dracula"));
        m.put("defaultTheme", wenyanProps.getDefaultTheme());
        m.put("highlight", wenyanProps.getHighlight());
        m.put("macStyle", wenyanProps.isMacStyle());
        m.put("footnote", wenyanProps.isFootnote());
        return R.ok(m);
    }

    private static Long requireProjectId(Map<String, Object> body) {
        Long projectId = toLong(body.get("projectId"), "projectId");
        if (projectId == null) throw new IllegalArgumentException("缺少 projectId");
        return projectId;
    }
}