package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.domain.dto.ImageGenDTO;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import com.sparkora.service.ImageService;
import com.sparkora.service.ImageTagService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配图接口（S3b，字段级契约见 docs/s0-spec.md §10）。
 * - VIEWER 可读图库；ADMIN/EDITOR 可上传/生成/选定封面插图。
 * - AI 生成接口耗时较长，前端单独放宽超时（同 generate/versions 模式）。
 * - body 里的 projectId/refImageId 做健壮解析：前端可能传字符串(路由参数)或数字，均接受。
 * - 09-13 image-tags：图库标签（上传/AI 生图预选随图入库、单图全量覆盖、批量打标/移除、标签筛选与清单）。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService service;
    private final ImageTagService tagService;
    private final com.sparkora.config.WenyanProperties wenyanProps;
    private final com.sparkora.service.PreviewService previewService;

    public ImageController(ImageService service, ImageTagService tagService,
                           com.sparkora.config.WenyanProperties wenyanProps,
                           com.sparkora.service.PreviewService previewService) {
        this.service = service;
        this.tagService = tagService;
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

    /** 图库分页列表（S10）：?projectId=&source=&keyword=&tag=&page=&size= 组合查询，响应 PageResult。
     *  09-13 image-tags：tag 筛选与其他筛选可组合；rows 每条含 tags（按名称排序）。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<PageResult<ImageAssetEntity>> list(@RequestParam(required = false) Long projectId,
                                                 @RequestParam(required = false) String source,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "24") long size) {
        try {
            return R.ok(service.list(projectId, source, keyword, tag, page, size));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
    }

    /** 全库标签清单（09-13 image-tags）：[{name, count}] count 降序，预选控件与筛选联想同源复用。 */
    @GetMapping("/tags")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<Map<String, Object>>> listTags() {
        try {
            return R.ok(tagService.listAll());
        } catch (Exception ex) {
            return R.fail(500, "获取标签失败: " + ex.getMessage());
        }
    }

    /** 上传图库图（projectId 可选=全局图库）：multipart file + tags?（多值/逗号分隔均可）。
     *  类型 png/jpg/webp，≤ IMAGE_MAX_UPLOAD_MB；09-13 起预选标签随图入库。 */
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ImageAssetEntity> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(required = false) List<String> tags) {
        try {
            CurrentUser cu = SecurityUtil.require();
            return R.ok(service.upload(projectId, file, splitMultipartTags(tags), cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (org.springframework.web.multipart.MultipartException ex) {
            Throwable root = ex.getRootCause() != null ? ex.getRootCause() : ex;
            return R.fail(400, "上传失败: " + root.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "上传失败: " + ex.getMessage());
        }
    }

    /** multipart tags 参数解析：多值参数收齐 + 单值内逗号拆分后合并（normalize 统一由服务层做）。 */
    private static List<String> splitMultipartTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            for (String part : s.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out.isEmpty() ? null : out;
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

    /** 文生图：body {projectId?, prompt, size?, n?, tags?[]}。S10：@Valid DTO + 响应改候选列表（n 张逐张入库）。
     *  09-13 image-tags：tags 为随图入库的预选标签（可空=不打标）。 */
    @PostMapping("/generate-text")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ImageAssetEntity>> generateText(@Validated @RequestBody ImageGenDTO body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = toLong(body.getProjectId(), "projectId");
            return R.ok(service.generateText2Image(projectId, body.getPrompt(), body.getSize(), body.getN(), body.getTags(), cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 图生图：body {projectId?, refImageId, prompt, size?, n?, tags?[]}。S10：@Valid DTO + 响应改候选列表。
     *  09-13 image-tags：tags 为随图入库的预选标签（可空=不打标）。 */
    @PostMapping("/generate-from-image")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<List<ImageAssetEntity>> generateFromImage(@Validated @RequestBody ImageGenDTO body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            Long projectId = toLong(body.getProjectId(), "projectId");
            Long refImageId = toLong(body.getRefImageId(), "refImageId");
            if (refImageId == null) return R.fail(400, "缺少 refImageId");
            return R.ok(service.generateImage2Image(projectId, refImageId, body.getPrompt(), body.getSize(), body.getN(), body.getTags(), cu.getUsername()));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 重新生成（S10）：同源图 prompt/gen_size 产新图（不覆盖源图）。09-13 起新图继承源图标签。 */
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

    /** 单图标签全量覆盖（09-13 image-tags）：body {tags:[...]}——用户在多选框勾/删后提交,空数组=清空。 */
    @PutMapping("/{id}/tags")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> updateTags(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            List<String> norm = tagService.normalize(toStringList(body == null ? null : body.get("tags")));
            tagService.replaceTags(id, norm, cu.getUsername());
            Map<String, Object> m = new HashMap<>();
            m.put("ok", true);
            m.put("tags", tagService.tagNamesOf(id));
            return R.ok(m);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "保存标签失败: " + ex.getMessage());
        }
    }

    /** 批量打标/移除（09-13 image-tags）：body {ids:[...], tags:[...], action:"add"|"remove"}。逐张执行,全部幂等。 */
    @PostMapping("/tags/batch")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> batchTags(@RequestBody Map<String, Object> body) {
        try {
            CurrentUser cu = SecurityUtil.require();
            List<Long> ids = toLongList(body == null ? null : body.get("ids"));
            if (ids == null || ids.isEmpty()) return R.fail(400, "请先选择图片");
            List<String> tags = toStringList(body.get("tags"));
            String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
            tagService.batchApply(ids, tags, action, cu.getUsername());
            Map<String, Object> m = new HashMap<>();
            m.put("ok", true);
            return R.ok(m);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "批量操作失败: " + ex.getMessage());
        }
    }

    /** ids 数组健壮解析：元素兼容 Number/字符串形式（前端可能传字符串 id）。 */
    private static List<Long> toLongList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<Long> out = new ArrayList<>();
        for (Object v : list) {
            Long id = toLong(v, "ids");
            if (id != null) out.add(id);
        }
        return out;
    }

    /** tags 数组健壮解析：元素统一 String.valueOf（防非字符串元素触发 ClassCastException）。 */
    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object v : list) {
            if (v != null) out.add(String.valueOf(v));
        }
        return out;
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