package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.ai.AiException;
import com.sparkora.ai.AiImageClient;
import com.sparkora.config.ImageProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 配图服务（S3b）。三来源统一入库：upload / ai-text2img / ai-img2img。
 *
 * 要点：
 *  - 上传校验：png/jpg/webp，≤ IMAGE_MAX_UPLOAD_MB；
 *  - AI 生成（文生图/图生图）返回的 URL（或 data URL）一律转存本地，失败则整次报错，不留死链；
 *  - 文件落 {IMAGE_STORAGE_DIR}/yyyy/MM/uuid.ext，/images/** 由 WebConfig 静态映射；
 *  - 封面/插图挂当前版本（ArticleVersionEntity.coverImageId/bodyImageIds），增删幂等；
 *  - 配图并入预览步骤：不再有独立「完成配图」状态推进（VERSIONS_READY 后直接可预览/发布）。
 */
@Slf4j
@Service
public class ImageService {

    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of("png", "jpg", "jpeg", "webp");
    /** OpenAI 兼容 size 参数白名单；auto/空由客户端层不传。 */
    private static final java.util.Set<String> ALLOWED_SIZES = java.util.Set.of("1024x1024", "1536x1024", "1024x1536");

    /** 转存 axonhub 临时 URL 的读超时。 */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(30);

    private final ImageProperties imageProps;
    private final ImageAssetMapper imageMapper;
    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final AiImageClient aiImageClient;
    private final QiniuService qiniuService;

    private final java.net.http.HttpClient transferClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public ImageService(ImageProperties imageProps, ImageAssetMapper imageMapper,
                        ArticleProjectMapper projectMapper, ArticleVersionMapper versionMapper,
                        AiImageClient aiImageClient, QiniuService qiniuService) {
        this.imageProps = imageProps;
        this.imageMapper = imageMapper;
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.aiImageClient = aiImageClient;
        this.qiniuService = qiniuService;
    }

    // ==================== 上传 ====================

    /** 上传不再限定必须在项目流程内:图库独立维护,projectId 允许为空(全局图库)。 */
    public ImageAssetEntity upload(Long projectId, MultipartFile file, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        long maxBytes = imageProps.getMaxUploadMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes)
            throw new IllegalArgumentException("图片超过大小上限 " + imageProps.getMaxUploadMb() + "MB");
        String ext = extOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext))
            throw new IllegalArgumentException("仅支持 png/jpg/webp 格式图片");

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
        // 内容校验:扩展名之外再验魔数,防止把非图片内容伪装成 .png 存进图库
        String sniffed = sniffExact(bytes);
        boolean ok = sniffed != null
                && (sniffed.equals(ext) || ("jpg".equals(sniffed) && "jpeg".equals(ext)));
        if (!ok) throw new IllegalArgumentException("文件内容不是有效的 png/jpg/webp 图片");

        ImageAssetEntity e = new ImageAssetEntity();
        e.setProjectId(projectId);
        e.setFileName(safeName(file.getOriginalFilename(), "upload.png"));
        e.setStoragePath(store(bytes, ext));
        e.setSource("upload");
        fillSize(e, bytes);
        e.setCreatedBy(operator);
        e.setCreatedAt(LocalDateTime.now());
        imageMapper.insert(e);
        log.info("上传配图 project={} id={} file={}（{}KB）", projectId, e.getId(), e.getFileName(), file.getSize() / 1024);
        return e;
    }

    // ==================== 文生图 / 图生图 ====================

    /** 文生图（图库独立维护,projectId 允许为 null = 不挂项目的全局图）。 */
    public ImageAssetEntity generateText2Image(Long projectId, String prompt, String size, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请输入生成提示词（prompt）");
        String url = aiImageClient.generateText2Image(prompt, normalizeSize(size));
        return saveGenerated(projectId, url, prompt, null, "ai-text2img", operator);
    }

    /** 图生图（projectId 允许为 null;参考图可来自任意图库）。 */
    public ImageAssetEntity generateImage2Image(Long projectId, Long refImageId, String prompt, String size, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请输入生成提示词（prompt）");
        if (refImageId == null) throw new IllegalArgumentException("请选择参考图");
        ImageAssetEntity ref = imageMapper.selectById(refImageId);
        if (ref == null) throw new IllegalArgumentException("参考图不存在");
        byte[] refBytes = readLocalBytes(ref.getStoragePath());
        String url = aiImageClient.generateImage2Image(prompt, refBytes, fileBaseName(ref.getStoragePath()), normalizeSize(size));
        return saveGenerated(projectId, url, prompt, refImageId, "ai-img2img", operator);
    }

    /** AI 生成结果统一转存本地（临时 URL/data URL 均不留存）。转存失败整次报错，不留死链。 */
    private ImageAssetEntity saveGenerated(Long projectId, String url, String prompt,
                                           Long refImageId, String source, String operator) {
        byte[] bytes = fetchBytes(url);
        String ext = sniffExt(bytes);
        ImageAssetEntity e = new ImageAssetEntity();
        e.setProjectId(projectId);
        e.setFileName(promptSummary(prompt) + "." + ext);
        e.setStoragePath(store(bytes, ext));
        e.setSource(source);
        e.setPromptText(prompt.length() > 2000 ? prompt.substring(0, 2000) : prompt);
        e.setRefImageId(refImageId);
        fillSize(e, bytes);
        e.setCreatedBy(operator);
        e.setCreatedAt(LocalDateTime.now());
        imageMapper.insert(e);
        log.info("AI 配图已转存 project={} id={} source={}（{}KB）", projectId, e.getId(), source, bytes.length / 1024);
        return e;
    }

    // ==================== 查询 / 封面 / 插图 / 完成配图 ====================

    /** 图库列表（projectId 可选过滤；按 id 倒序，最新在前）。 */
    public List<ImageAssetEntity> list(Long projectId) {
        QueryWrapper<ImageAssetEntity> qw = new QueryWrapper<>();
        if (projectId != null) qw.eq("project_id", projectId);
        qw.orderByDesc("id");
        return imageMapper.selectList(qw);
    }

    /**
     * 删除图库图（ADMIN/EDITOR）。被封面/插图引用时拒绝并给出引用方提示，避免版本配图死链。
     * 删除落库记录 + 本地文件；文件缺失不阻断（记录照删）。
     */
    public void delete(Long id) {
        ImageAssetEntity img = imageMapper.selectById(id);
        if (img == null) throw new IllegalArgumentException("图片不存在");
        // 引用检查:先用 like 粗筛候选(避免全表遍历),再按 bodyIdList 精确判定——
        // like 子串匹配会把 id=5 误匹配到 15/51,粗筛后必须精确过滤,否则合法删除被误拒
        List<ArticleVersionEntity> candidates = versionMapper.selectList(new QueryWrapper<ArticleVersionEntity>()
                .eq("cover_image_id", id)
                .or().like("body_image_ids", String.valueOf(id)));
        List<ArticleVersionEntity> refs = candidates.stream()
                .filter(v -> id.equals(v.getCoverImageId()) || bodyIdListOf(v).contains(id))
                .toList();
        if (!refs.isEmpty()) {
            List<String> marks = refs.stream().map(v -> "项目#" + v.getProjectId() + "版本#" + v.getId())
                    .toList();
            throw new IllegalArgumentException("图片正被引用（" + String.join("、", marks) + "），请先在对应预览步骤移除后再删除");
        }
        imageMapper.deleteById(id);
        // 同步删七牛对象(非阻塞,失败仅告警;qiniu_key 为空=未上床,直接跳过)
        qiniuService.deleteObject(img.getQiniuKey());
        try {
            java.nio.file.Files.deleteIfExists(imageProps.storageRoot().resolve(img.getStoragePath()));
        } catch (IOException e) {
            log.warn("图片文件删除失败（记录已删）: {} {}", img.getStoragePath(), e.getMessage());
        }
        log.info("删除配图 id={} path={}", id, img.getStoragePath());
    }

    /** 配图快照（三角色可读）：全量图库（含全局图，与配图选用口径一致）+ 当前版本封面/插图。 */
    public Map<String, Object> projectImages(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity current = currentVersion(p);
        Map<String, Object> m = new java.util.HashMap<>();
        // 口径:配图步骤可选用任意库内图(全局图与各项目图),因此快照的 images 用全量图库
        m.put("images", list(null));
        m.put("currentVersionId", current == null ? null : current.getId());
        m.put("coverImageId", current == null ? null : current.getCoverImageId());
        m.put("bodyImageIds", bodyIdListOf(current));
        return m;
    }

    /** 选封面（重复选同一张幂等）。图可来自全局图库(项目匹配放开校验)。 */
    public void setCover(Long projectId, Long imageId) {
        ArticleVersionEntity v = requireVersionWithImages(projectId);
        ImageAssetEntity img = imageMapper.selectById(imageId);
        if (img == null)
            throw new IllegalArgumentException("图片不存在");
        if (imageId.equals(v.getCoverImageId())) return;   // 幂等
        v.setCoverImageId(imageId);
        versionMapper.updateById(v);
    }

    /** 增/删正文插图（action=add/remove，均幂等）。图可来自全局图库。 */
    public void modifyBodyImage(Long projectId, Long imageId, String action) {
        ArticleVersionEntity v = requireVersionWithImages(projectId);
        boolean add;
        if ("add".equals(action)) add = true;
        else if ("remove".equals(action)) add = false;
        else throw new IllegalArgumentException("action 仅支持 add/remove");
        if (add && imageMapper.selectById(imageId) == null)
            throw new IllegalArgumentException("图片不存在");
        List<Long> bodyIdList = new ArrayList<>(bodyIdListOf(v));
        if (add) {
            if (!bodyIdList.contains(imageId)) bodyIdList.add(imageId);   // 幂等
        } else {
            bodyIdList.remove(imageId);                                    // 不存在也视为成功
        }
        v.setBodyImageIds(bodyIdList.isEmpty() ? null
                : bodyIdList.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        versionMapper.updateById(v);
    }

    // ==================== 内部工具 ====================

    private void ensureProject(Long projectId) {
        if (projectMapper.selectById(projectId) == null) throw new IllegalArgumentException("项目不存在");
    }

    /** 封面/插图操作前置：项目存在 + 已有当前版本（未生成版本时不可配图）。 */
    private ArticleVersionEntity requireVersionWithImages(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity v = currentVersion(p);
        if (v == null) throw new IllegalArgumentException("尚未生成正文版本，无法配图");
        return v;
    }

    private ArticleVersionEntity currentVersion(ArticleProjectEntity p) {
        if (p.getCurrentVersionId() == null) return null;
        return versionMapper.selectById(p.getCurrentVersionId());
    }

    private static List<Long> bodyIdListOf(ArticleVersionEntity v) {
        if (v == null || v.getBodyImageIds() == null || v.getBodyImageIds().isBlank()) return new ArrayList<>();
        return Arrays.stream(v.getBodyImageIds().split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }

    private String normalizeSize(String size) {
        if (size == null || size.isBlank() || "auto".equalsIgnoreCase(size.trim())) return null;
        String s = size.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_SIZES.contains(s)) throw new IllegalArgumentException("不支持的尺寸: " + s);
        return s;
    }

    /** 落盘 {root}/yyyy/MM/uuid.ext，返回相对路径（/images/ 的 URL 尾段）。 */
    private String store(byte[] bytes, String ext) {
        try {
            String rel = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                    + "/" + UUID.randomUUID() + "." + ext;
            Path target = imageProps.storageRoot().resolve(rel);
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, bytes);
            return rel;
        } catch (IOException e) {
            throw new RuntimeException("图片落盘失败: " + e.getMessage(), e);
        }
    }

    /** 下载 AI 返回的 URL（data URL 直接解码；http(s) 走 HttpClient）。 */
    private byte[] fetchBytes(String url) {
        try {
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0) throw new AiException("data URL 格式非法", null);
                return java.util.Base64.getDecoder().decode(url.substring(comma + 1));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TRANSFER_TIMEOUT).GET().build();
            HttpResponse<byte[]> resp = transferClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200)
                throw new AiException("转存图片失败: HTTP " + resp.statusCode(), null);
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) throw new AiException("转存图片为空", null);
            return bytes;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("转存图片失败: " + e.getMessage(), e);
        }
    }

    /** 宽高探测：失败不阻断（留空）。 */
    private void fillSize(ImageAssetEntity e, byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                e.setWidth(img.getWidth());
                e.setHeight(img.getHeight());
            }
        } catch (Exception ex) {
            log.debug("读取图片尺寸失败（忽略）: {}", ex.getMessage());
        }
    }

    byte[] readLocalBytes(String storagePath) {
        // 越界断言(纵深防御):storagePath 虽全由服务端生成,仍防 resolve 逃出根目录
        Path target = imageProps.storageRoot().resolve(storagePath).normalize();
        if (!target.startsWith(imageProps.storageRoot()))
            throw new IllegalArgumentException("非法存储路径");
        try {
            return java.nio.file.Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("读取参考图失败: " + storagePath, e);
        }
    }

    private static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 存储文件扩展名：魔数嗅探（png/jpg/webp）。
     * 嗅探不出时兜底返回 png —— 仅用于「从 AI 下载的字节流」命名（provider 生成必为图片）。
     * 上传校验场景须用 {@link #sniffExact(byte[])}（返回 null 表示非图片，拒绝）。
     */
    private static String sniffExt(byte[] bytes) {
        String s = sniffExact(bytes);
        return s == null ? "png" : s;
    }

    /** 严格魔数嗅探：识别不出返回 null（不兜底），供上传内容校验使用。 */
    private static String sniffExact(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return "png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return "jpg";
        // webp: RIFF....WEBP
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        return null;
    }

    /** 参考图传给 multipart 的文件名。 */
    private static String fileBaseName(String storagePath) {
        int slash = storagePath.lastIndexOf('/');
        String name = slash < 0 ? storagePath : storagePath.substring(slash + 1);
        return name.isBlank() ? "reference.png" : name;
    }

    /** 生成图文件名用 prompt 摘要（替换非法字符，超长截断）。 */
    private static String promptSummary(String prompt) {
        String s = prompt == null ? "image" : prompt.replaceAll("[\\\\/:*?\"<>|\\s]+", "-").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isBlank() ? "image" : s;
    }

    private static String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        return name.replaceAll("[\\\\/:*?\"<>|]+", "_");
    }
}