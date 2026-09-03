package com.sparkora.service;

import com.sparkora.config.ImageProperties;
import com.sparkora.config.WenyanProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * S4 预览服务(方案 A):文颜同核渲染。
 *
 * 链路:当前版本 content_md + 封面/插图(七牛公网 URL)→ 组装 frontmatter/URL 化正文
 *   → 本机 wenyan render(-t theme 等,与远程 server 同核 @wenyan-md/core)→ 公众号排版 HTML。
 * 纯渲染不碰微信;图片全部为七牛公网 URL(浏览器直接加载=所见即所得,server 发布时按 URL 拉取)。
 *
 * 降级:wenyan CLI 不可达/超时/失败 → 简化 HTML 保底渲染,degraded=true 并带原因。
 */
@Slf4j
@Service
public class PreviewService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final ImageService imageService;
    private final QiniuService qiniuService;
    private final ImageProperties imageProps;
    private final WenyanProperties wenyanProps;
    private final WenyanServerService serverService;

    public PreviewService(ArticleProjectMapper projectMapper, ArticleVersionMapper versionMapper,
                          ImageService imageService, QiniuService qiniuService,
                          ImageProperties imageProps, WenyanProperties wenyanProps,
                          WenyanServerService serverService) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.imageService = imageService;
        this.qiniuService = qiniuService;
        this.imageProps = imageProps;
        this.wenyanProps = wenyanProps;
        this.serverService = serverService;
    }

    /**
     * 生成预览。返回 {html, theme, highlight, macStyle, footnote, degraded, degradedReason?}。
     */
    public Map<String, Object> preview(Long projectId, String theme, String highlight,
                                       Boolean macStyle, Boolean footnote) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (p.getCurrentVersionId() == null) throw new IllegalStateException("尚未生成正文版本，无法预览");
        if (!"VERSIONS_READY".equals(p.getStatus()) && !"PUBLISHED_DRAFT".equals(p.getStatus()))
            throw new IllegalStateException("项目状态为 " + p.getStatus() + "，版本就绪后才可预览");
        ArticleVersionEntity v = versionMapper.selectById(p.getCurrentVersionId());
        if (v == null) throw new IllegalStateException("当前版本不存在，请重新选定");

        String th = validTheme(theme);
        String hl = validHighlight(highlight);
        boolean mac = macStyle == null ? wenyanProps.isMacStyle() : macStyle;
        boolean fn = footnote == null ? wenyanProps.isFootnote() : footnote;

        // 1) 图片 URL 化:封面 + 正文插图全转七牛公网 URL(懒转存,已转存复用)
        String coverUrl = v.getCoverImageId() == null ? null : qiniuService.ensureUploaded(v.getCoverImageId());
        List<String> bodyUrls = new ArrayList<>();
        for (Long imageId : bodyIdListOf(v)) bodyUrls.add(qiniuService.ensureUploaded(imageId));

        // 2) 组装 markdown:frontmatter(title/author/cover)+ 正文;
        //    正文里本地 /images/ 引用替换为七牛 URL;插图落点完全由正文 markdown 引用决定,
        //    未引用的选定插图不自动追加(与预览页 buildFullMd 同规则,所见即所得)
        String md = buildMarkdown(v.getTitle(), coverUrl, v.getContentMd(), bodyUrls);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("theme", th);
        result.put("highlight", hl);
        result.put("macStyle", mac);
        result.put("footnote", fn);

        // 3) wenyan render(纯排版,不碰微信);失败时降级保底渲染并透传单次原因
        String html = null;
        String degradeReason = null;
        try {
            html = renderByCli(md, th, hl, mac, fn);
        } catch (Exception e) {
            log.warn("wenyan render 失败,降级保底渲染: {}", e.toString());
            degradeReason = e.getMessage();
        }
        if (html == null || html.isBlank()) {
            result.put("degraded", true);
            result.put("degradedReason", "wenyan 渲染不可用，已降级为普通 Markdown 预览。"
                    + (degradeReason == null ? "" : "详情: " + degradeReason));
            html = fallbackRender(md);
        } else {
            result.put("degraded", false);
        }
        result.put("html", html);
        return result;
    }

    /**
     * frontmatter(title/cover=七牛 URL)+ 正文;正文本地 /images/ 引用替换为七牛 URL。
     * 插图落点完全由正文 markdown 引用决定:未引用的选定插图不自动追加文末(与预览页 buildFullMd 同规则,所见即所得)。
     */
    private String buildMarkdown(String title, String coverUrl, String contentMd, List<String> bodyImageUrls) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(sanitizeFrontmatterValue(title == null || title.isBlank() ? "无标题" : title)).append('\n');
        if (coverUrl != null && !coverUrl.isBlank()) sb.append("cover: ").append(coverUrl).append('\n');
        sb.append("---\n\n");
        String body = contentMd == null ? "" : contentMd.replace("<br>", "\n");
        // 本地相对引用 URL 化:/images/2026/xx.png → 七牛;对应资产才能找到(按文件名在图库反查已转存的图)
        body = replaceLocalImages(body);
        sb.append(body).append('\n');
        return sb.toString();
    }

    /** 把正文里的本地 /images/ 引用替换为七牛公网 URL( wenyan-server 只能拉公网 URL)。 */
    private String replaceLocalImages(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("!\\[[^]]*]\\((/images/[^)]+)\\)").matcher(body);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String localPath = m.group(1);   // /images/2026/08/uuid.png
            String storageRel = localPath.substring("/images/".length());
            String url;
            try {
                url = qiniuService.ensureUploadedByStoragePath(storageRel);
            } catch (Exception e) {
                log.warn("正文本地图转存失败(保留原样): {} {}", localPath, e.getMessage());
                url = localPath;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("![](" + url + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 主题名白名单校验(防 CLI 参数注入;清单来自 .env 的 WENYAN_THEME_NAMES)。 */
    private String validTheme(String theme) {
        if (theme == null || theme.isBlank()) return wenyanProps.getDefaultTheme();
        return wenyanProps.themeNameList().stream()
                .filter(t -> t.equalsIgnoreCase(theme.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知主题: " + theme + "（可选：" + wenyanProps.themeNameList() + "）"));
    }

    // ==================== 选项/配置透出(publish-options 与发布通道检查,S5) ====================

    /** 可选主题清单(白名单同源)。 */
    public java.util.List<String> themeOptions() {
        return wenyanProps.themeNameList();
    }

    /** 默认主题。 */
    public String defaultTheme() {
        return wenyanProps.getDefaultTheme();
    }

    /** 默认高亮主题。 */
    public String defaultHighlight() {
        return wenyanProps.getHighlight();
    }

    /** 默认 Mac 风格开关。 */
    public boolean defaultMacStyle() {
        return wenyanProps.isMacStyle();
    }

    /** 默认脚注开关。 */
    public boolean defaultFootnote() {
        return wenyanProps.isFootnote();
    }

    /** 发布通道配置是否齐备(serverUrl + serverApiKey)。 */
    public boolean serverConfigured() {
        return wenyanProps.serverConfigured();
    }

    /** 发布通道鉴权探针(GET /verify);不可达/无配置返回 false。 */
    public boolean serverVerify() {
        if (!wenyanProps.serverConfigured()) return false;
        return serverService.verify();
    }

    /** wenyan-server 版本描述(探活失败返回不可达提示,不抛异常)。 */
    public String serverHealth() {
        try {
            return serverService.health();
        } catch (Exception e) {
            return "不可达";
        }
    }

    /** 高亮主题白名单(与 preview-options 的 highlights 口径一致)。 */
    private String validHighlight(String highlight) {
        if (highlight == null || highlight.isBlank()) return wenyanProps.getHighlight();
        List<String> allowed = List.of("solarized-light", "monokai", "github", "dracula");
        return allowed.stream().filter(t -> t.equalsIgnoreCase(highlight.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知高亮主题: " + highlight + "（可选：" + allowed + "）"));
    }

    /**
     * 调本机 wenyan CLI render,返回 HTML(子进程输出异步排水防管道阻塞,超时/非零退出/空输出均抛错由上层降级)。
     * 临时 md 文件用 createTempMd() 落盘:系统 /tmp 在受限容器里可能只读。
     */
    private String renderByCli(String markdown, String theme, String highlight,
                               boolean macStyle, boolean footnote) {
        Path tmp = null;
        try {
            tmp = createTempMd();
            Files.write(tmp, markdown.getBytes(StandardCharsets.UTF_8));
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    wenyanProps.getCliPath(), "render",
                    "--theme", theme,
                    "--highlight", highlight));
            if (!macStyle) cmd.add("--no-mac-style");
            if (!footnote) cmd.add("--no-footnote");
            cmd.add("--file");
            cmd.add(tmp.toAbsolutePath().toString());

            Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            // 管道排水:stdout/stderr 超过缓冲区(约64K)会阻塞子进程,必须先异步读完再 waitFor
            java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
            Thread drainOut = drain(proc.getInputStream(), outBuf);
            Thread drainErr = drain(proc.getErrorStream(), errBuf);
            boolean finished = proc.waitFor(wenyanProps.getRenderTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IllegalStateException("wenyan 渲染超时");
            }
            drainOut.join(2000);
            drainErr.join(2000);
            String out = outBuf.toString(StandardCharsets.UTF_8).trim();
            if (proc.exitValue() != 0 || out.isBlank()) {
                String err = errBuf.toString(StandardCharsets.UTF_8).trim();
                throw new IllegalStateException(err.isEmpty() ? "wenyan 渲染无输出" : err);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignore) { }
            }
        }
    }

    private static Thread drain(java.io.InputStream in, java.io.ByteArrayOutputStream buf) {
        Thread t = new Thread(() -> {
            try { buf.write(in.readAllBytes()); } catch (IOException ignore) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 创建渲染用临时 md 文件。系统临时目录(java.io.tmpdir,默认 /tmp)在受限容器/只读挂载下不可写,
     * 因此首选 {IMAGE_STORAGE_DIR}/../tmp/preview(项目数据盘,随应用配置落位),失败再回退系统临时目录。
     */
    private Path createTempMd() throws IOException {
        try {
            Path dir = imageProps.storageRoot().resolve("../tmp/preview").normalize();
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "sparkora-preview-", ".md");
        } catch (IOException primaryFail) {
            log.info("数据盘临时目录不可用({}),回退系统临时目录: {}", primaryFail.getMessage(), System.getProperty("java.io.tmpdir"));
        }
        return Files.createTempFile("sparkora-preview-", ".md");
    }

    /** 降级保底:极简 markdown 渲染(标题/粗斜体/链接/图片),先 HTML 转义再替换(降级路径 XSS 修复)。 */
    private String fallbackRender(String markdown) {
        String body = markdown == null ? "" : markdown.replaceAll("(?s)^---.*?---", "").trim();
        String escaped = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        String html = escaped
                .replaceAll("(?m)^#\\s+(.*)$", "<h1>$1</h1>")
                .replaceAll("(?m)^##\\s+(.*)$", "<h2>$1</h2>")
                .replaceAll("(?m)^###\\s+(.*)$", "<h3>$1</h3>")
                .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(?<!\\*)\\*([^*]+?)\\*(?!\\*)", "<em>$1</em>")
                // 图片/链接允许 http(s) 与七牛公网 URL;src/href 拒绝 javascript: 等协议
                .replaceAll("!\\[(.*?)]\\((https?://[^)\\s]+)\\)",
                        "<img src=\"$2\" alt=\"$1\" style=\"max-width:100%\" />")
                .replaceAll("(?<!\\!)\\[(.*?)]\\((https?://[^)\\s]+)\\)",
                        "<a href=\"$2\">$1</a>")
                .replaceAll("(?m)^$", "<br/>");
        return "<section style=\"line-height:1.8;font-size:15px\">" + html + "</section>";
    }

    /** frontmatter 值清洗(引号换行防坏结构)。 */
    private static String sanitizeFrontmatterValue(String s) {
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static List<Long> bodyIdListOf(ArticleVersionEntity v) {
        if (v == null || v.getBodyImageIds() == null || v.getBodyImageIds().isBlank()) return new ArrayList<>();
        return Arrays.stream(v.getBodyImageIds().split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }
}