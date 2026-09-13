package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.dto.PageResult;
import com.sparkora.ai.AiException;
import com.sparkora.ai.AiImageClient;
import com.sparkora.config.ImageProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配图服务（S3b + S6）。五来源统一入库：upload / ai-text2img / ai-img2img / byd / byd-news（09-13 image-tags）。
 *
 * 要点（S6 图库完全依赖图床，本地不留）：
 *  - 上传校验：png/jpg/webp，≤ IMAGE_MAX_UPLOAD_MB；
 *  - AI 生成（文生图/图生图）返回的 URL（或 data URL）一律下载字节后直接转存图床，失败则整次报错，不留死链；
 *  - 图片入库即直接转存图床（storageKey），本地不落盘；
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
    private final ImageStorage imageStorage;
    /** 七牛配置（可选注入：图床供应商非七牛时 bean 不存在，thumbUrl 降级为原图 url）。 */
    private final ObjectProvider<QiniuProperties> qiniuProps;
    /** 图片标签服务（09-13 image-tags：入库管线落标/列表回填/删图清理）。 */
    private final ImageTagService tagService;

    private final java.net.http.HttpClient transferClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public ImageService(ImageProperties imageProps, ImageAssetMapper imageMapper,
                        ArticleProjectMapper projectMapper, ArticleVersionMapper versionMapper,
                        AiImageClient aiImageClient, ImageStorage imageStorage,
                        ObjectProvider<QiniuProperties> qiniuProps, ImageTagService tagService) {
        this.imageProps = imageProps;
        this.imageMapper = imageMapper;
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.aiImageClient = aiImageClient;
        this.imageStorage = imageStorage;
        this.qiniuProps = qiniuProps;
        this.tagService = tagService;
    }

    // ==================== 上传 ====================

    /** 上传不再限定必须在项目流程内:图库独立维护,projectId 允许为空(全局图库)。图片直接转存图床。
     *  09-13 image-tags:tags 为随图入库的预选标签(可 null=不打标)。 */
    public ImageAssetEntity upload(Long projectId, MultipartFile file, List<String> tags, String operator) {
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

        ImageAssetEntity preset = new ImageAssetEntity();
        preset.setProjectId(projectId);
        preset.setFileName(safeName(file.getOriginalFilename(), "upload.png"));
        preset.setSource("upload");
        preset.setCreatedBy(operator);
        preset.setTags(tagService.normalize(tags));   // 预选标签随 preset 走统一管线落标
        ImageAssetEntity e = persistOrReuse(bytes, ext, preset);
        if (Boolean.TRUE.equals(e.getDedupeHit())) {
            log.info("上传去重复用已有记录 id={} file={}（{}KB）", e.getId(), e.getFileName(), file.getSize() / 1024);
        } else {
            log.info("上传配图 project={} id={} file={}（{}KB）", projectId, e.getId(), e.getFileName(), file.getSize() / 1024);
        }
        return e;
    }

    // ==================== 文生图 / 图生图 ====================

    /** 文生图（图库独立维护,projectId 允许为 null = 不挂项目的全局图）。
     *  S10 M3：返回 URL 列表 + 实际命中模型名，循环 n 次单张入库（单张失败跳过，全部失败抛 AiException）。
     *  09-13 image-tags:tags 为随图入库的预选标签(可 null=不打标)。 */
    public List<ImageAssetEntity> generateText2Image(Long projectId, String prompt, String size, int n,
                                                     List<String> tags, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请输入生成提示词（prompt）");
        int count = n < 1 ? 1 : Math.min(n, 4);
        String normSize = normalizeSize(size);
        List<String> normTags = tagService.normalize(tags);
        List<ImageAssetEntity> out = new ArrayList<>();
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < count; i++) {
            try {
                AiImageClient.GenResult g = aiImageClient.generateText2Image(prompt, normSize);
                out.add(saveGenerated(projectId, g.url(), prompt, null, "ai-text2img", operator, g.model(), normSize, normTags));
            } catch (Exception e) {
                log.warn("文生图第 {} 张失败（跳过）: {}", i + 1, e.getMessage());
                errs.append("第").append(i + 1).append("张: ").append(e.getMessage()).append("; ");
            }
        }
        if (out.isEmpty()) throw new AiException("文生图全部失败: " + errs, null);
        return out;
    }

    /** 图生图（projectId 允许为 null;参考图可来自任意图库）。S10 M3：循环 n 次单张入库。
     *  09-13 image-tags:tags 为随图入库的预选标签(可 null=不打标)。 */
    public List<ImageAssetEntity> generateImage2Image(Long projectId, Long refImageId, String prompt, String size,
                                                      int n, List<String> tags, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请输入生成提示词（prompt）");
        if (refImageId == null) throw new IllegalArgumentException("请选择参考图");
        ImageAssetEntity ref = imageMapper.selectById(refImageId);
        if (ref == null) throw new IllegalArgumentException("参考图不存在");
        byte[] refBytes = imageStorage.download(ref.getStorageKey());
        int count = n < 1 ? 1 : Math.min(n, 4);
        String normSize = normalizeSize(size);
        List<String> normTags = tagService.normalize(tags);
        List<ImageAssetEntity> out = new ArrayList<>();
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < count; i++) {
            try {
                AiImageClient.GenResult g = aiImageClient.generateImage2Image(prompt, refBytes, fileBaseName(ref.getFileName()), normSize);
                out.add(saveGenerated(projectId, g.url(), prompt, refImageId, "ai-img2img", operator, g.model(), normSize, normTags));
            } catch (Exception e) {
                log.warn("图生图第 {} 张失败（跳过）: {}", i + 1, e.getMessage());
                errs.append("第").append(i + 1).append("张: ").append(e.getMessage()).append("; ");
            }
        }
        if (out.isEmpty()) throw new AiException("图生图全部失败: " + errs, null);
        return out;
    }

    /** 重新生成（S10 M3）：用源图 prompt/gen_size 产新图（不覆盖源图）。返回 1 张候选。
     *  09-13 image-tags：新图继承源图标签（同主题成组）；重生成不读全局预选标签。 */
    public List<ImageAssetEntity> regenerate(Long imageId, String operator) {
        ImageAssetEntity src = imageMapper.selectById(imageId);
        if (src == null) throw new IllegalArgumentException("图片不存在: " + imageId);
        if (!java.util.Set.of("ai-text2img", "ai-img2img").contains(src.getSource()))
            throw new IllegalArgumentException("仅 AI 生成图可重新生成");
        if (src.getPromptText() == null || src.getPromptText().isBlank())
            throw new IllegalArgumentException("源图无 prompt，无法重新生成");
        List<ImageAssetEntity> out;
        if ("ai-img2img".equals(src.getSource())) {
            if (src.getRefImageId() == null) throw new IllegalArgumentException("源图无参考图，无法重新生成");
            out = generateImage2Image(orphanFallback(src.getProjectId()), src.getRefImageId(), src.getPromptText(),
                    src.getGenSize(), 1, null, operator);
        } else {
            out = generateText2Image(orphanFallback(src.getProjectId()), src.getPromptText(), src.getGenSize(), 1, null, operator);
        }
        // 继承源图标签：生成成功后复制(源图→新图);同内容去重命中已有图时 merge 语义兜底
        for (ImageAssetEntity e : out) {
            tagService.copyTags(imageId, e.getId(), operator);
            e.setTags(tagService.tagNamesOf(e.getId()));
        }
        return out;
    }

    /** 源图挂的项目若已被删除则回退全局图库（orphan 记录不阻断 AI 图重生成）。 */
    private Long orphanFallback(Long projectId) {
        if (projectId == null) return null;
        return projectMapper.selectById(projectId) == null ? null : projectId;
    }

    /** AI 生成结果统一转存图床（临时 URL/data URL 均不留存）。转存失败整次报错，不留死链。
     *  S10 起走统一入库管线（内容哈希去重）；M3 起留档 gen_model/gen_size；
     *  09-13 image-tags 起透传预选标签 tags 走管线落标。 */
    private ImageAssetEntity saveGenerated(Long projectId, String url, String prompt,
                                           Long refImageId, String source, String operator,
                                           String genModel, String genSize, List<String> tags) {
        byte[] bytes = fetchBytes(url);
        String ext = sniffExt(bytes);
        ImageAssetEntity preset = new ImageAssetEntity();
        preset.setProjectId(projectId);
        preset.setFileName(promptSummary(prompt) + "." + ext);
        preset.setSource(source);
        preset.setPromptText(prompt.length() > 2000 ? prompt.substring(0, 2000) : prompt);
        preset.setRefImageId(refImageId);
        preset.setGenModel(genModel);
        preset.setGenSize(genSize);
        preset.setCreatedBy(operator);
        preset.setTags(tags);
        ImageAssetEntity e = persistOrReuse(bytes, ext, preset);
        if (Boolean.TRUE.equals(e.getDedupeHit())) {
            log.info("AI 配图去重复用 id={} source={}（{}KB）", e.getId(), source, bytes.length / 1024);
        } else {
            log.info("AI 配图已转存图床 project={} id={} source={}（{}KB）", projectId, e.getId(), source, bytes.length / 1024);
        }
        return e;
    }

    // ==================== 查询 / 封面 / 插图 / 完成配图 ====================

    /** 来源参数白名单（spec §10；非法值 400）。09-13 image-tags 起新增 byd-news（比亚迪新闻封面）。 */
    private static final java.util.Set<String> SOURCES = java.util.Set.of("upload", "ai-text2img", "ai-img2img", "byd", "byd-news");

    /**
     * 统一入库管线（S10 去重内聚）：五来源（upload/文生图/图生图/BYD 车型图/BYD 新闻封面）共用。
     * 计算 sha256 → 查 content_hash 命中则复用已有记录（不重复传图床，dedupeHit=true）→ 未命中则上传图床 + insert。
     * 09-13 image-tags 打标钩子：preset.tags 非空时——新入库 saveTags；去重命中 mergeTags
     * （把本次预选但已有图缺失的标签补写到已有图上，用户预选必须生效）；回填 entity.tags 供响应携带。
     * @param preset 由调用方填充领域字段（source/projectId/promptText/refImageId/genModel/genSize/fileName/createdBy/tags），
     *               本方法负责 contentHash/storageKey/width/height/createdAt 落库与 url/thumbUrl 回填。
     * @return 已入库（或复用）的实体；dedupeHit=true 表示命中已有记录（前端提示「复用」）。
     */
    public ImageAssetEntity persistOrReuse(byte[] bytes, String ext, ImageAssetEntity preset) {
        String hash = sha256Hex(bytes);
        ImageAssetEntity hit = imageMapper.selectOne(new QueryWrapper<ImageAssetEntity>().eq("content_hash", hash).last("LIMIT 1"));
        if (hit != null) {
            fillDerived(hit);
            hit.setDedupeHit(true);
            applyPresetTags(hit, preset, true);   // 命中已有图:merge 补上本次预选但缺失的标签
            log.info("配图去重命中 hash={} 复用记录 id={}（未上传图床）", hash, hit.getId());
            return hit;
        }
        preset.setContentHash(hash);
        preset.setStorageKey(imageStorage.upload(bytes, ext));
        fillSize(preset, bytes);
        preset.setCreatedAt(LocalDateTime.now());
        imageMapper.insert(preset);
        fillDerived(preset);
        applyPresetTags(preset, preset, false);   // 新入库:全量写标签
        return preset;
    }

    /** 落标钩子：preset.tags 非空时按「新图 saveTags / 命中 mergeTags」写入目标图，并回填 entity.tags 供响应携带。 */
    private void applyPresetTags(ImageAssetEntity target, ImageAssetEntity preset, boolean dedupeHit) {
        List<String> norm = tagService.normalize(preset.getTags());
        if (norm.isEmpty()) {
            // 无预选标签时也回填已有标签(命中复用场景前端可见原图标签;新图为空列表)
            target.setTags(dedupeHit ? tagService.tagNamesOf(target.getId()) : List.of());
            return;
        }
        if (dedupeHit) {
            tagService.mergeTags(target.getId(), norm, preset.getCreatedBy());
        } else {
            tagService.saveTags(target.getId(), norm, preset.getCreatedBy());
        }
        target.setTags(tagService.tagNamesOf(target.getId()));
    }

    /**
     * 外部图片下载入库（09-13 image-tags，BYD 新闻封面等外部 URL 场景）：
     * 下载字节（超时 30s）→ 魔数嗅探 → 统一入库管线（persistOrReuse，含去重与标签钩子）。
     * 相对 URL 自动拼 https://www.byd.com 前缀（参照前端 resolveUrl 语义）。
     * 下载/嗅探失败抛 RuntimeException，由调用方 catch 决定是否阻断（新闻封面仅告警不阻断）。
     * @param operator 标签与记录的创建人（如 "system"）
     */
    public ImageAssetEntity saveExternalImage(Long projectId, String url, String fileName, String source,
                                              List<String> tags, String operator) {
        String absolute = resolveBydUrl(url);
        byte[] bytes = fetchExternalBytes(absolute);
        String ext = sniffExt(bytes);
        ImageAssetEntity preset = new ImageAssetEntity();
        preset.setProjectId(projectId);
        preset.setFileName(ensureExt(safeName(fileName, "external"), ext));
        preset.setSource(source);
        preset.setTags(tags);
        preset.setCreatedBy(operator);
        return persistOrReuse(bytes, ext, preset);
    }

    /** 文件名扩展名以魔数嗅探结果为准：调用方预判与实际不符时重写扩展名（防 .jpg 存 webp 内容）。 */
    private static String ensureExt(String fileName, String ext) {
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        return base + "." + ext;
    }

    /** 外部相对 URL 解析：http(s) 开头原样返回，否则拼 BYD 官网域名（参照前端 resolveUrl 语义）。 */
    private static String resolveBydUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) throw new IllegalArgumentException("图片 URL 为空");
        if (u.toLowerCase(Locale.ROOT).startsWith("http://") || u.toLowerCase(Locale.ROOT).startsWith("https://")) return u;
        return "https://www.byd.com" + (u.startsWith("/") ? u : "/" + u);
    }

    /** 下载外部图片字节（超时 30s，同 TRANSFER_TIMEOUT 量级）；非 200/空内容抛异常。 */
    private byte[] fetchExternalBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TRANSFER_TIMEOUT).GET().build();
            HttpResponse<byte[]> resp = transferClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("空内容");
            return bytes;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new RuntimeException("下载图片失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("下载图片失败: " + e.getMessage(), e);
        }
    }

    /** SHA-256 → hex（入库去重用；图库规模小，查询走 content_hash 索引）。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败: " + e.getMessage(), e);
        }
    }

    /** 图库分页列表（S10：服务端筛选 + 分页，替代「全量拉取 + 前端过滤」）。
     *  09-13 image-tags：新增 tag 筛选（两段查询：先按标签名查 image_id 集，再 IN 主表），
     *  rows 回填 tags（页内批查，避免 N+1）；tag 命中集超 500 截前 500 保查询稳定。 */
    public PageResult<ImageAssetEntity> list(Long projectId, String source, String keyword, String tag, long page, long size) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 24;
        if (source != null && !source.isBlank() && !SOURCES.contains(source))
            throw new IllegalArgumentException("不支持的图片来源: " + source);
        QueryWrapper<ImageAssetEntity> qw = new QueryWrapper<>();
        if (projectId != null) qw.eq("project_id", projectId);
        if (source != null && !source.isBlank()) qw.eq("source", source);
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) qw.and(w -> w.like("file_name", kw).or().like("prompt_text", kw));
        if (tag != null && !tag.isBlank()) {
            // 两段查询(QueryWrapper 无 JOIN):先查标签命中 id 集(走 idx_image_tag_name),空集直接空页
            List<Long> ids = tagService.imageIdsByTag(tag.trim());
            if (ids.isEmpty()) return new PageResult<>(List.of(), 0, page, size);
            if (ids.size() > 500) ids = ids.subList(0, 500);   // 命中集截断保底(图库分页 ≤100/页)
            qw.in("id", ids);
        }
        qw.orderByDesc("id");
        Page<ImageAssetEntity> p = imageMapper.selectPage(new Page<>(page, size), qw);
        p.getRecords().forEach(this::fillDerived);
        tagService.fillTags(p.getRecords());   // 页内批查回填标签
        return new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 填充非持久化 url 字段（由 storageKey 拼图床公网 URL）。 */
    private void fillUrl(ImageAssetEntity img) {
        if (img.getStorageKey() != null && !img.getStorageKey().isBlank()) {
            img.setUrl(imageStorage.publicUrl(img.getStorageKey()));
        }
    }

    /** 填充非持久化 thumbUrl（S10：七牛 imageView2/webp 派生；非七牛实现降级为原图 url）。 */
    private void fillThumbUrl(ImageAssetEntity img) {
        QiniuProperties q = qiniuProps.getIfAvailable();
        if (q != null && q.configured() && img.getUrl() != null) {
            img.setThumbUrl(q.thumbUrl(img.getStorageKey()));
        } else {
            img.setThumbUrl(img.getUrl());
        }
    }

    /** 列表/快照共用的展示派生字段填充（url + thumbUrl）。 */
    private void fillDerived(ImageAssetEntity img) {
        fillUrl(img);
        fillThumbUrl(img);
    }

    /** 由图库记录 id 取图床公网 URL（图片入库即已转存，storageKey 非空）。 */
    public String publicUrl(Long imageId) {
        ImageAssetEntity img = imageMapper.selectById(imageId);
        if (img == null) throw new IllegalArgumentException("图片不存在: " + imageId);
        if (img.getStorageKey() == null || img.getStorageKey().isBlank())
            throw new IllegalStateException("图片未转存图床: " + imageId);
        return imageStorage.publicUrl(img.getStorageKey());
    }

    /**
     * 删除图库图（ADMIN/EDITOR）。被封面/插图引用时拒绝并给出引用方提示，避免版本配图死链。
     * 删除落库记录 + 图床对象（非阻塞，失败仅 warn）。
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
        tagService.deleteByImageId(id);   // 09-13:标签行生命周期=图片生命周期,删图联动物理清(同 KB embedding 兜底先例)
        imageMapper.deleteById(id);
        // 同步删图床对象(非阻塞,失败仅告警)
        imageStorage.delete(img.getStorageKey());
        log.info("删除配图 id={} key={}", id, img.getStorageKey());
    }

    /**
     * 配图快照（三角色可读）。S10 语义改写：
     * images 从「全量图库」收缩为「当前版本引用的图」（封面 + 插图，按 bodyImageIds 顺序在前）；
     * 新增服务端解析的 coverImage / bodyImages（含 url，StepPublish/StepPreview 消费方不再自行 find）。
     * 图库全量浏览走分页接口 GET /api/images（抽屉选图网格同源）。
     */
    public Map<String, Object> projectImages(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity current = currentVersion(p);
        Long coverImageId = current == null ? null : current.getCoverImageId();
        List<Long> bodyImageIds = bodyIdListOf(current);
        // 引用图集合 = 封面 + 插图；批量查询 + 内存排序（保持 bodyImageIds 顺序，封面置前）
        java.util.LinkedHashSet<Long> refIds = new java.util.LinkedHashSet<>();
        if (coverImageId != null) refIds.add(coverImageId);
        refIds.addAll(bodyImageIds);
        Map<Long, ImageAssetEntity> byId = refIds.isEmpty() ? Map.of()
                : imageMapper.selectBatchIds(refIds).stream()
                        .collect(Collectors.toMap(ImageAssetEntity::getId, e -> e));
        List<ImageAssetEntity> bodyImages = bodyImageIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .peek(this::fillDerived).toList();
        ImageAssetEntity coverImage = coverImageId == null ? null : byId.get(coverImageId);
        if (coverImage != null) fillDerived(coverImage);
        List<ImageAssetEntity> images = new ArrayList<>();
        if (coverImage != null) images.add(coverImage);
        images.addAll(bodyImages);
        tagService.fillTags(images);   // 09-13:引用图集合回填标签(选封面/插图弹窗可见)

        Map<String, Object> m = new java.util.HashMap<>();
        m.put("images", images);                 // S10 起为「当前版本引用的图」（不再是全量图库）
        m.put("currentVersionId", current == null ? null : current.getId());
        m.put("coverImageId", coverImageId);
        m.put("bodyImageIds", bodyImageIds);
        m.put("coverImage", coverImage);
        m.put("bodyImages", bodyImages);
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
    private static String fileBaseName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "reference.png";
        return fileName;
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
