package com.sparkora.web.controller;

import com.sparkora.common.R;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.SecurityUtil;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目配图（图库选用/封面/正文插图）与配图建议子域接口。
 *
 * 09-27-split-monoliths：从 {@link ArticleProjectController} 按子域拆出，方法体逐字搬迁（零行为变化）。
 * 字段级契约见 docs/spec/image.md。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectImageController {

    private final com.sparkora.service.ImageService imageService;
    private final com.sparkora.service.IllustrationSuggestionService suggestionService;

    public ProjectImageController(com.sparkora.service.ImageService imageService,
                                  com.sparkora.service.IllustrationSuggestionService suggestionService) {
        this.imageService = imageService;
        this.suggestionService = suggestionService;
    }

    // ==================== 配图（S3b，字段级契约见 docs/spec/image.md）====================

    /** 配图快照：项目全部图 + 当前版本封面/插图（三角色可读）。 */
    @GetMapping("/images")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<java.util.Map<String, Object>> projectImages(@PathVariable Long projectId) {
        return R.ok(imageService.projectImages(projectId));
    }

    /** 选封面（幂等）。 */
    @PostMapping("/images/{imageId}/cover")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> setCover(@PathVariable Long projectId, @PathVariable Long imageId) {
        try {
            imageService.setCover(projectId, imageId);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    /** 增/删正文插图（?action=add|remove，幂等）。 */
    @PostMapping("/images/{imageId}/body")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> modifyBodyImage(@PathVariable Long projectId, @PathVariable Long imageId,
                                   @RequestParam(defaultValue = "add") String action) {
        try {
            imageService.modifyBodyImage(projectId, imageId, action);
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, ex.getMessage());
        }
    }

    // ==================== 配图建议（09-15 article-auto-illustrate，子C；字段级契约见 docs/spec/image.md）====================

    /**
     * 生成按锚点分组的配图建议（三角色可读）。
     *
     * **零副作用**：只读正文与图库做语义检索，不修改 {@code content_md} 与版本插图关联行（{@code sparkora_article_version_image}）。
     * 配图进入正文的唯一路径是用户在预览页显式点「采用」（无任何自动插入开关）。
     * body: {"tags":["主题/销量"], "minScore":0.3}（均可选）。
     */
    @PostMapping("/illustration-suggestions")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<List<com.sparkora.service.IllustrationSuggestionService.AnchorSuggestion>> illustrationSuggestions(
            @PathVariable Long projectId, @RequestBody(required = false) java.util.Map<String, Object> body) {
        try {
            return R.ok(suggestionService.suggest(projectId, stringListOf(body == null ? null : body.get("tags")),
                    doubleOf(body == null ? null : body.get("minScore"))));
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "生成配图建议失败: " + ex.getMessage());
        }
    }

    /**
     * 忽略某锚点的建议组（ADMIN/EDITOR）：该锚点后续不再推荐（避免反复打扰）。
     * body: {"anchorKey":"a1b2c3d4e5f6"}。幂等：重复忽略不报错。
     */
    @PostMapping("/illustration-suggestions/dismiss")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Void> dismissIllustrationSuggestion(@PathVariable Long projectId,
                                                 @RequestBody java.util.Map<String, String> body) {
        try {
            CurrentUser cu = SecurityUtil.current();
            suggestionService.dismiss(projectId, body == null ? null : body.get("anchorKey"),
                    cu == null ? "system" : cu.getUsername());
            return R.ok();
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        } catch (Exception ex) {
            return R.fail(500, "忽略配图建议失败: " + ex.getMessage());
        }
    }

    // ==================== 请求体解析工具（配图建议）====================

    /** JSON 数组 → List&lt;String&gt;；元素不保证是字符串（前端可能传数字），统一 String.valueOf 归一。 */
    private static List<String> stringListOf(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = new java.util.ArrayList<>();
        for (Object v : list) {
            if (v != null) out.add(String.valueOf(v));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * JSON number → Double；兼容字符串形式（前端控件可能传字符串），缺省/非法返回 null
     * （由服务层按配置默认收敛，不报错）。与「请求体数字字段统一健壮解析」既有惯例一致。
     */
    private static Double doubleOf(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
