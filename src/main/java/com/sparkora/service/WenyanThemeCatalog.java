package com.sparkora.service;

import com.sparkora.config.ImageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wenyan 主题权威目录(8 内置 + 7 社区)。
 *
 * 背景(详见 docs/wenyan.md 与 spec §11/§12):
 *  - 内置主题由本机 wenyan CLI 原生识别,渲染时传 `--theme <id>`;
 *  - 社区主题(mdnice 7 个)CLI 不识别,须用 `--custom-theme <本地CSS绝对路径>` 渲染,
 *    且该参数不支持网络 URL,故 CSS 必须随后端包内置(classpath:wenyan-themes/*.css)。
 *
 * jar 安全:classpath 资源打包进 jar 后 getFile() 不可用,故启动时把 CSS 物化到数据盘
 * ({IMAGE_STORAGE_DIR}/../tmp/wenyan-themes/,与 PreviewService 临时 md 同一数据盘策略),
 * 缓存绝对路径供 CLI 渲染使用。物化失败仅告警,该主题渲染时抛错走既有降级链。
 */
@Slf4j
@Component
public class WenyanThemeCatalog {

    /** 主题元数据:id / 显示名 / 分组(builtin|community) / 色点 / 是否亮色点。 */
    public record ThemeMeta(String id, String name, String group, String color, boolean bright) {}

    /** 内置主题(与 wenyan CLI 2.0.11 内置清单一致;色点与前端历史 THEME_COLORS 对齐)。 */
    private static final List<ThemeMeta> BUILTIN = List.of(
            new ThemeMeta("default", "default", "builtin", "#1a73e8", false),
            new ThemeMeta("orangeheart", "orangeheart", "builtin", "#ef7060", false),
            new ThemeMeta("rainbow", "rainbow", "builtin", "#e91e63", true),
            new ThemeMeta("lapis", "lapis", "builtin", "#4870ac", false),
            new ThemeMeta("pie", "pie", "builtin", "#2b2b2b", false),
            new ThemeMeta("maize", "maize", "builtin", "#ffb11b", true),
            new ThemeMeta("purple", "purple", "builtin", "#8e44ad", false),
            new ThemeMeta("phycat", "phycat", "builtin", "#3eaf7c", false)
    );

    /** 社区主题(id 规范 custom:&lt;slug&gt;,中文显示名与色点取自原前端 wenyanThemes.js)。 */
    private static final List<ThemeMeta> COMMUNITY = List.of(
            new ThemeMeta("custom:chazi", "姹紫", "community", "#773098", false),
            new ThemeMeta("custom:mohei", "墨黑", "community", "#5c5c5c", false),
            new ThemeMeta("custom:nenqin", "嫩青", "community", "#47c1a8", false),
            new ThemeMeta("custom:hongfei", "红绯", "community", "#f83929", false),
            new ThemeMeta("custom:lanqing", "兰青", "community", "#009688", false),
            new ThemeMeta("custom:shanchui", "山吹", "community", "#ffb11b", true),
            new ThemeMeta("custom:quanzhanlan", "全栈蓝", "community", "#3594f7", false)
    );

    private static final List<ThemeMeta> ALL;

    static {
        ALL = new java.util.ArrayList<>(BUILTIN.size() + COMMUNITY.size());
        ALL.addAll(BUILTIN);
        ALL.addAll(COMMUNITY);
    }

    private final ImageProperties imageProps;

    /** 社区主题 id → 已物化的 CSS 绝对路径(物化失败则不含该键)。 */
    private final Map<String, String> communityCssPaths = new LinkedHashMap<>();

    public WenyanThemeCatalog(ImageProperties imageProps) {
        this.imageProps = imageProps;
    }

    /** 启动时物化社区 CSS 到数据盘(幂等覆盖),缓存绝对路径。 */
    @PostConstruct
    void materializeCommunityCss() {
        Path dir = imageProps.storageRoot().resolve("../tmp/wenyan-themes").normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("社区主题 CSS 目录不可用({}),社区主题将走降级链: {}", dir, e.getMessage());
            return;
        }
        for (ThemeMeta meta : COMMUNITY) {
            String slug = meta.id().substring("custom:".length());
            try {
                Path target = dir.resolve(slug + ".css").normalize();
                try (InputStream in = new ClassPathResource("wenyan-themes/" + slug + ".css").getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                communityCssPaths.put(meta.id(), target.toAbsolutePath().toString());
            } catch (IOException e) {
                log.warn("社区主题 CSS 物化失败({}),该主题将走降级链: {}", meta.id(), e.getMessage());
            }
        }
    }

    /** 全量主题目录(内置在前,社区在后)。 */
    public List<ThemeMeta> all() {
        return ALL;
    }

    /** 全部合法主题 id(校验用)。 */
    public List<String> ids() {
        return ALL.stream().map(ThemeMeta::id).toList();
    }

    /** 是否社区主题(需走 --custom-theme)。 */
    public boolean isCommunity(String id) {
        return id != null && id.startsWith("custom:");
    }

    /** 按 id 取主题元数据;未知抛 IllegalArgumentException(中文提示,控制器映射 400)。 */
    public ThemeMeta require(String id) {
        if (id == null) throw new IllegalArgumentException("未知主题: null（可选：" + ids() + "）");
        String key = id.trim();
        return ALL.stream()
                .filter(t -> t.id().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知主题: " + id + "（可选：" + ids() + "）"));
    }

    /** 社区主题返回已物化的 CSS 绝对路径;内置主题返回 null。物化失败时抛中文异常(走降级链)。 */
    public String cssPath(String id) {
        if (!isCommunity(id)) return null;
        String path = communityCssPaths.get(require(id).id());
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("社区主题样式未就绪: " + id + "（后端资源物化失败）");
        }
        return path;
    }
}
