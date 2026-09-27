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
import com.sparkora.domain.entity.ArticleVersionImageEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionImageMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
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
 *  - 封面挂当前版本（ArticleVersionEntity.coverImageId）；正文插图挂关联表
 *    sparkora_article_version_image（P1-⑦ 规范化，一行一图 + sort_order 保序），增删幂等；
 *  - 配图并入预览步骤：不再有独立「完成配图」状态推进（VERSIONS_READY 后直接可预览/发布）。
 */
@Slf4j
@Service
public class ImageService {

    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of("png", "jpg", "jpeg", "webp");
    /**
     * OpenAI 兼容 size 参数白名单；auto/空由客户端层不传。
     * 09-27-img-gen-size-ux：白名单**保持 3 值不变**（扩值要撞 axonhub 下游未验证的 WxH 支持矩阵）。
     * 前端不直接暴露像素，而是给用户 4 档「比例」，映射到本白名单（映射表单一真源 = 前端
     * {@code frontend/src/utils/imageGenRatio.js}，后端只认像素、不参与该语义）：
     * <pre>
     *   1:1  → 1024x1024（精确）
     *   4:3  → 1536x1024（实际 3:2）
     *   3:4  → 1024x1536（实际 2:3）
     *   9:16 → 1024x1536（实际 2:3，手机全屏近似）
     * </pre>
     * 16:9 与 4:3 同为 1536x1024，故前端不提供该档（不给两个指向同一像素的选项）。
     * gen_size 落库仍是像素 ⇒ 本表与本注释是长期契约：新增像素值须同步前端映射与 docs/spec/image.md §6。
     */
    private static final java.util.Set<String> ALLOWED_SIZES = java.util.Set.of("1024x1024", "1536x1024", "1024x1536");

    /** 转存 axonhub 临时 URL 的读超时。 */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(30);

    private final ImageProperties imageProps;
    private final ImageAssetMapper imageMapper;
    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    /** 版本-正文插图关联表 mapper（P1-⑦ 原逗号列规范化后的读写入口）。 */
    private final ArticleVersionImageMapper versionImageMapper;
    private final AiImageClient aiImageClient;
    private final ImageStorage imageStorage;
    /** 七牛配置（可选注入：图床供应商非七牛时 bean 不存在，thumbUrl 降级为原图 url）。 */
    private final ObjectProvider<QiniuProperties> qiniuProps;
    /** 图片标签服务（09-13 image-tags：入库管线落标/列表回填/删图清理）。 */
    private final ImageTagService tagService;
    /** 新闻主表 mapper（09-15 img-classify：来源追溯 source_ref → 新闻元信息）；新闻域可选，缺失时降级 news:null。 */
    private final ObjectProvider<com.sparkora.mapper.NewsMapper> newsMapper;
    /** 图片语义向量服务（09-15 img-semantic-search：入库后 best-effort 嵌向量、删图联动清向量）。 */
    private final ImageEmbeddingService embeddingService;

    private final java.net.http.HttpClient transferClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public ImageService(ImageProperties imageProps, ImageAssetMapper imageMapper,
                        ArticleProjectMapper projectMapper, ArticleVersionMapper versionMapper,
                        ArticleVersionImageMapper versionImageMapper,
                        AiImageClient aiImageClient, ImageStorage imageStorage,
                        ObjectProvider<QiniuProperties> qiniuProps, ImageTagService tagService,
                        ObjectProvider<com.sparkora.mapper.NewsMapper> newsMapper,
                        ImageEmbeddingService embeddingService) {
        this.imageProps = imageProps;
        this.imageMapper = imageMapper;
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.versionImageMapper = versionImageMapper;
        this.aiImageClient = aiImageClient;
        this.imageStorage = imageStorage;
        this.qiniuProps = qiniuProps;
        this.tagService = tagService;
        this.newsMapper = newsMapper;
        this.embeddingService = embeddingService;
    }

    // ==================== 上传 ====================

    /** 上传不再限定必须在项目流程内:图库独立维护,projectId 允许为空(全局图库)。图片直接转存图床。
     *  09-13 image-tags:tags 为随图入库的预选标签(可 null=不打标)。 */
    public ImageAssetEntity upload(Long projectId, MultipartFile file, List<String> tags, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        String ext = extOf(file.getOriginalFilename());
        byte[] bytes = readValidatedImage(file);

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

    /**
     * 图片 multipart 校验（{@link #upload} 与图生图参考图直传 {@link #generateImage2ImageFromUpload} 共用，
     * 提取自 upload，行为/文案零改动）：大小 ≤ IMAGE_MAX_UPLOAD_MB → 扩展名白名单 → 读字节 → 魔数嗅探。
     * 空文件判定留调用方（两处文案不同：图片 vs 参考图）。
     * @return 图片字节（内存中；upload 用于入库转存，参考图直传用于直传 AI——均不在此方法落库）
     */
    private byte[] readValidatedImage(MultipartFile file) {
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
        return bytes;
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
                // 09-26 img2img-multi-ref：单图图库路径同步传单元素列表，行为不变
                AiImageClient.GenResult g = aiImageClient.generateImage2Image(prompt, List.of(refBytes),
                        List.of(fileBaseName(ref.getFileName())), normSize);
                out.add(saveGenerated(projectId, g.url(), prompt, refImageId, "ai-img2img", operator, g.model(), normSize, normTags));
            } catch (Exception e) {
                log.warn("图生图第 {} 张失败（跳过）: {}", i + 1, e.getMessage());
                errs.append("第").append(i + 1).append("张: ").append(e.getMessage()).append("; ");
            }
        }
        if (out.isEmpty()) throw new AiException("图生图全部失败: " + errs, null);
        return out;
    }

    /**
     * 图生图（参考图文件字节直传 + 图库参考图集合，09-26 img2img-multi-ref）：参考图**不落图库**——
     * 上传文件仅在内存校验后字节直传 AI（/v1/images/edits）；图库参考图取字节后同样直传，不上图床、不 insert、不嵌向量。
     * 生成结果照旧走统一入库管线（source=ai-img2img）。
     *
     * 参考图集合顺序：先上传文件（按请求顺序），后图库 id（按请求顺序），**后端不重排**；
     * 总数须 1~4（0 → 400「请至少选择 1 张参考图」；>4 → 400「最多支持 4 张参考图」）。
     * `ref_image_id` 落值：参考图数==1 且唯一来源为图库 → 落该 id；其余（多图 / 仅文件来源）→ NULL
     * （列只能存一个，多图落 id 会误导重生成）。
     *
     * @param files       上传参考图（可空；逐张大小/扩展名/魔数校验）
     * @param refImageIds 图库参考图 id（可空；不存在 → 400「参考图不存在」）
     */
    public List<ImageAssetEntity> generateImage2ImageFromUpload(Long projectId, List<MultipartFile> files,
                                                                List<Long> refImageIds, String prompt,
                                                                String size, int n, List<String> tags, String operator) {
        if (projectId != null) ensureProject(projectId);
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请输入生成提示词（prompt）");
        List<MultipartFile> normFiles = files == null ? List.of()
                : files.stream().filter(java.util.Objects::nonNull).toList();
        List<Long> normIds = refImageIds == null ? List.of()
                : refImageIds.stream().filter(java.util.Objects::nonNull).toList();
        int refCount = normFiles.size() + normIds.size();
        if (refCount == 0) throw new IllegalArgumentException("请至少选择 1 张参考图");
        if (refCount > 4) throw new IllegalArgumentException("最多支持 4 张参考图");

        // 保序合并参考图：先上传文件（逐张校验）→ 后图库 id（selectById + 图床下载），后端不重排
        List<byte[]> refBytesList = new ArrayList<>();
        List<String> refNameList = new ArrayList<>();
        for (MultipartFile f : normFiles) {
            if (f.isEmpty()) throw new IllegalArgumentException("请选择要上传的参考图");
            byte[] refBytes = readValidatedImage(f);
            refBytesList.add(refBytes);
            // 传给 multipart 的文件名扩展名以魔数嗅探为准（防 .jpg 装 webp 内容），沿用 ensureExt 语义
            refNameList.add(ensureExt(safeName(f.getOriginalFilename(), "reference.png"), sniffExt(refBytes)));
        }
        Long singleLibRefId = null;
        for (Long id : normIds) {
            ImageAssetEntity ref = imageMapper.selectById(id);
            if (ref == null) throw new IllegalArgumentException("参考图不存在");
            refBytesList.add(imageStorage.download(ref.getStorageKey()));
            refNameList.add(fileBaseName(ref.getFileName()));
            singleLibRefId = id;
        }
        // ref_image_id 规则：参考图数==1 且唯一来源为图库 → 该 id；否则 NULL（多图/仅文件来源无法自引用，不走 /regenerate）
        Long resultRefImageId = (refCount == 1 && normFiles.isEmpty()) ? singleLibRefId : null;

        int count = n < 1 ? 1 : Math.min(n, 4);
        String normSize = normalizeSize(size);
        List<String> normTags = tagService.normalize(tags);
        List<ImageAssetEntity> out = new ArrayList<>();
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < count; i++) {
            try {
                AiImageClient.GenResult g = aiImageClient.generateImage2Image(prompt, refBytesList, refNameList, normSize);
                out.add(saveGenerated(projectId, g.url(), prompt, resultRefImageId, "ai-img2img", operator, g.model(), normSize, normTags));
            } catch (Exception e) {
                log.warn("图生图(参考图直传)第 {} 张失败（跳过）: {}", i + 1, e.getMessage());
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
            embeddingService.embedQuietly(e.getId());   // 09-15:标签继承后重嵌，嵌入文本与最终标签保持一致
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

    /** 来源参数白名单（docs/spec/image.md；非法值 400）。09-13 image-tags 起新增 byd-news（比亚迪新闻封面）。 */
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
            applyPresetSourceRef(hit, preset);    // 09-15:来源串只在缺失时补写(同图被多新闻引用保留首次值)
            embeddingService.embedQuietly(hit.getId());   // 09-15 img-semantic-search:去重命中同样确保有向量(缺向量图由重建补齐)
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
        // 09-15 img-semantic-search:入库成功后 best-effort 嵌向量(钩子在 insert 之后,embedQuietly 自吞异常,
        // 绝不影响入库链路——标签/来源已落库,嵌入文本才能取到完整信号)
        embeddingService.embedQuietly(preset.getId());
        return preset;
    }

    /**
     * 来源串钩子（09-15 img-classify）：去重命中已有图时，仅当已有 source_ref 为空才补写本次来源
     * （同一图片被不同新闻引用时保留首次值，不覆盖；历史已追溯的图不被重同步改写）。
     *
     * 并发安全：条件写在 UPDATE 的 WHERE 里（原子条件更新，同 database-guidelines「原子抢占」范式），
     * 而非只依赖 Java 端先读后判——先读后写存在 check-then-set 竞态窗口（两条新闻并发同步同一张图时
     * 都读到空值，后写覆盖先写，违背「保留首次值」语义）。WHERE 命中 0 行说明已被并发写入，
     * 此时以库中现有值为准回填实体，避免响应体与库不一致。
     */
    private void applyPresetSourceRef(ImageAssetEntity target, ImageAssetEntity preset) {
        String ref = preset.getSourceRef();
        if (ref == null || ref.isBlank()) return;
        if (target.getSourceRef() != null && !target.getSourceRef().isBlank()) return;
        int updated = imageMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ImageAssetEntity>()
                        .eq("id", target.getId())
                        .and(w -> w.isNull("source_ref").or().eq("source_ref", ""))
                        .set("source_ref", ref));
        if (updated > 0) {
            target.setSourceRef(ref);
            return;
        }
        ImageAssetEntity fresh = imageMapper.selectById(target.getId());
        target.setSourceRef(fresh == null ? ref : fresh.getSourceRef());
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
     * 09-15 img-classify 起加 sourceRef（来源引用串，新闻图=官方 news_id），透传落库。
     * @param operator 标签与记录的创建人（如 "system"）
     */
    public ImageAssetEntity saveExternalImage(Long projectId, String url, String fileName, String source,
                                              List<String> tags, String operator, String sourceRef) {
        String absolute = resolveBydUrl(url);
        byte[] bytes = fetchExternalBytes(absolute);
        String ext = sniffExt(bytes);
        ImageAssetEntity preset = new ImageAssetEntity();
        preset.setProjectId(projectId);
        preset.setFileName(ensureExt(safeName(fileName, "external"), ext));
        preset.setSource(source);
        preset.setTags(tags);
        preset.setCreatedBy(operator);
        preset.setSourceRef(sourceRef);
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
     *  rows 回填 tags（页内批查，避免 N+1）；tag 命中集超 500 截前 500 保查询稳定。
     *  09-15 img-classify：tag 从单值扩为多值，语义为 **AND**（须同时具备所有指定标签）；
     *  逐标签取 id 集求交集，交集为空直接返回空页（不查库）。传 1 个标签即原单标签语义（向后兼容）。 */
    public PageResult<ImageAssetEntity> list(Long projectId, String source, String keyword, List<String> tags,
                                             long page, long size) {
        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 24;
        if (source != null && !source.isBlank() && !SOURCES.contains(source))
            throw new IllegalArgumentException("不支持的图片来源: " + source);
        QueryWrapper<ImageAssetEntity> qw = new QueryWrapper<>();
        if (projectId != null) qw.eq("project_id", projectId);
        if (source != null && !source.isBlank()) qw.eq("source", source);
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) qw.and(w -> w.like("file_name", kw).or().like("prompt_text", kw));
        List<String> normTags = normalizeTagFilter(tags);
        if (!normTags.isEmpty()) {
            // 两段查询(QueryWrapper 无 JOIN):逐标签查命中 id 集(走 idx_image_tag_name),取交集实现 AND
            java.util.Set<Long> ids = resolveTagIds(normTags, tagService::imageIdsByTag);
            if (ids == null || ids.isEmpty()) return new PageResult<>(List.of(), 0, page, size);   // 无图同时命中全部标签
            if (ids.size() > 500) ids = new java.util.HashSet<>(new ArrayList<>(ids).subList(0, 500));   // 命中集截断保底
            qw.in("id", ids);
        }
        qw.orderByDesc("id");
        Page<ImageAssetEntity> p = imageMapper.selectPage(new Page<>(page, size), qw);
        p.getRecords().forEach(this::fillDerived);
        tagService.fillTags(p.getRecords());   // 页内批查回填标签
        return new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** tag 筛选参数归一：去空/trim/去重（保序）；null/空 → 空列表（不筛）。 */
    static List<String> normalizeTagFilter(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) continue;
            String v = t.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return List.copyOf(out);
    }

    /**
     * 多标签 AND 交集解算（纯函数，可单测）：对归一后每个标签取命中 id 集求交集。
     * 返回 null = 未指定标签（不筛）；返回空集 = 无图同时命中全部标签（调用方直接返回空页，不查库）。
     * @param lookup 标签名 → 命中图片 id 集（生产实现 = tagService.imageIdsByTag）
     */
    static java.util.Set<Long> resolveTagIds(List<String> tags, java.util.function.Function<String, List<Long>> lookup) {
        List<String> norm = normalizeTagFilter(tags);
        if (norm.isEmpty()) return null;
        java.util.Set<Long> ids = null;
        for (String tag : norm) {
            java.util.Set<Long> hit = new java.util.HashSet<>(lookup.apply(tag));
            if (ids == null) {
                ids = hit;
            } else {
                ids.retainAll(hit);
            }
            if (ids.isEmpty()) break;   // 交集已空，提前退出
        }
        return ids == null ? java.util.Set.of() : ids;
    }

    /** 填充非持久化 url 字段（由 storageKey 拼图床公网 URL）。 */
    private void fillUrl(ImageAssetEntity img) {
        fillUrl(img, imageStorage);
    }

    /** url 派生实现（静态：图库列表与图片语义检索命中回填共用同一规则）。 */
    static void fillUrl(ImageAssetEntity img, ImageStorage storage) {
        if (img.getStorageKey() != null && !img.getStorageKey().isBlank()) {
            img.setUrl(storage.publicUrl(img.getStorageKey()));
        }
    }

    /** 填充非持久化 thumbUrl（S10：七牛 imageView2/webp 派生；非七牛实现降级为原图 url）。 */
    private void fillThumbUrl(ImageAssetEntity img) {
        fillThumbUrl(img, imageStorage, qiniuProps.getIfAvailable());
    }

    /** thumbUrl 派生实现（静态：同 fillUrl）。 */
    static void fillThumbUrl(ImageAssetEntity img, ImageStorage storage, QiniuProperties q) {
        if (q != null && q.configured() && img.getUrl() != null) {
            img.setThumbUrl(q.thumbUrl(img.getStorageKey()));
        } else {
            img.setThumbUrl(img.getUrl());
        }
    }

    /** 列表/快照共用的展示派生字段填充（url + thumbUrl）。 */
    private void fillDerived(ImageAssetEntity img) {
        fillDerived(img, imageStorage, qiniuProps.getIfAvailable());
    }

    /**
     * 展示派生字段填充（静态实现，09-15 img-semantic-search 起被 `ImageEmbeddingService` 检索命中回填复用）：
     * 同一派生规则只此一处实现（url 由图床拼、thumbUrl 按供应商能力派生/降级）。
     */
    static void fillDerived(ImageAssetEntity img, ImageStorage storage, QiniuProperties q) {
        fillUrl(img, storage);
        fillThumbUrl(img, storage, q);
    }

    /**
     * 批量取图库记录 + 派生展示字段（url/thumbUrl）（09-15 qa-auto-illustrate）。
     *
     * **只读**：仅 selectBatchIds + fillDerived，不写任何列；供问答配图批量解析新闻封面
     * （避免 N+1，也避免在问答侧重新实现「storageKey→url / 七牛 thumbUrl」派生规则导致两处漂移）。
     * 不存在的 id 直接跳过（图已删/引用失效 → 该条配图不出图，不报错）。
     *
     * @param imageIds 图库资产 id 列表（可空/含 null）
     * @return 命中记录（带 url/thumbUrl）；顺序不保证（调用方按 id 索引）
     */
    public List<ImageAssetEntity> loadDerived(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return List.of();
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (Long id : imageIds) {
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        List<ImageAssetEntity> imgs = imageMapper.selectBatchIds(ids);
        if (imgs == null || imgs.isEmpty()) return List.of();
        QiniuProperties q = qiniuProps.getIfAvailable();
        for (ImageAssetEntity img : imgs) fillDerived(img, imageStorage, q);
        return imgs;
    }

    /** 由图库记录 id 取图床公网 URL（图片入库即已转存，storageKey 非空）。 */
    public String publicUrl(Long imageId) {
        ImageAssetEntity img = imageMapper.selectById(imageId);
        if (img == null) throw new IllegalArgumentException("图片不存在: " + imageId);
        if (img.getStorageKey() == null || img.getStorageKey().isBlank())
            throw new IllegalStateException("图片未转存图床: " + imageId);
        return imageStorage.publicUrl(img.getStorageKey());
    }

    /** 由图库记录 id 取图库公网 URL，不存在或未转存时返回 null（列表/派生字段填充用，不抛异常）。 */
    public String publicUrlQuietly(Long imageId) {
        if (imageId == null) return null;
        try {
            ImageAssetEntity img = imageMapper.selectById(imageId);
            if (img == null || img.getStorageKey() == null || img.getStorageKey().isBlank()) return null;
            return imageStorage.publicUrl(img.getStorageKey());
        } catch (Exception e) {
            log.warn("取图片公网 URL 失败(忽略) id={}: {}", imageId, e.getMessage());
            return null;
        }
    }

    /**
     * 图片来源追溯（09-15 img-classify）：返回 {sourceRef, news, imageUrl}。
     * 新闻图（source_ref = 官方 news_id）反查 sparkora_news 得标题/日期/原文链接；
     * 非新闻图或查无新闻 → news:null（HTTP 200 不报错，前端不展示来源行）。
     */
    public com.sparkora.domain.dto.ImageSourceDTO getSource(Long imageId) {
        ImageAssetEntity img = imageMapper.selectById(imageId);
        if (img == null) throw new IllegalArgumentException("图片不存在: " + imageId);
        String ref = img.getSourceRef();
        com.sparkora.domain.dto.ImageSourceDTO.NewsRef news = null;
        if (ref != null && !ref.isBlank()) {
            com.sparkora.mapper.NewsMapper nm = newsMapper.getIfAvailable();
            com.sparkora.domain.entity.NewsEntity n = nm == null ? null : nm.selectOne(
                    new QueryWrapper<com.sparkora.domain.entity.NewsEntity>().eq("news_id", ref).last("LIMIT 1"));
            if (n != null) {
                news = new com.sparkora.domain.dto.ImageSourceDTO.NewsRef(
                        n.getId(), n.getNewsId(), n.getTitle(), n.getPublishDate(), n.getUrl());
            }
        }
        String imageUrl = img.getStorageKey() == null || img.getStorageKey().isBlank()
                ? null : imageStorage.publicUrl(img.getStorageKey());
        return new com.sparkora.domain.dto.ImageSourceDTO(ref, news, imageUrl);
    }

    /**
     * 删除图库图（ADMIN/EDITOR）。被封面/插图引用时拒绝并给出引用方提示，避免版本配图死链。
     * 删除落库记录 + 图床对象（非阻塞，失败仅 warn）。
     */
    public void delete(Long id) {
        ImageAssetEntity img = imageMapper.selectById(id);
        if (img == null) throw new IllegalArgumentException("图片不存在");
        // 引用检查（P1-⑦）：封面走 cover_image_id 精确匹配，插图走关联表按 image_id 精确反查。
        // 原对逗号列的 LIKE 粗筛会把 id=5 误匹配到 15/51，已随规范化消除。
        List<ArticleVersionEntity> coverRefs = versionMapper.selectList(new QueryWrapper<ArticleVersionEntity>()
                .eq("cover_image_id", id));
        List<Long> bodyVersionIds = versionImageMapper.findVersionIdsByImage(id);
        java.util.LinkedHashMap<Long, ArticleVersionEntity> refs = new java.util.LinkedHashMap<>();
        coverRefs.forEach(v -> refs.put(v.getId(), v));
        if (!bodyVersionIds.isEmpty()) {
            for (ArticleVersionEntity v : versionMapper.selectBatchIds(bodyVersionIds)) refs.put(v.getId(), v);
        }
        if (!refs.isEmpty() || !bodyVersionIds.isEmpty()) {
            // 有封面或插图关联即为引用；版本行缺失的孤儿关联行（异常数据）同样拒绝，避免留下悬挂引用
            List<String> marks = refs.isEmpty()
                    ? bodyVersionIds.stream().distinct().map(vid -> "版本#" + vid).toList()
                    : refs.values().stream().map(v -> "项目#" + v.getProjectId() + "版本#" + v.getId()).toList();
            throw new IllegalArgumentException("图片正被引用（" + String.join("、", marks) + "），请先在对应预览步骤移除后再删除");
        }
        tagService.deleteByImageId(id);   // 09-13:标签行生命周期=图片生命周期,删图联动物理清(同 KB embedding 兜底先例)
        embeddingService.deleteByImageId(id);   // 09-15 img-semantic-search:向量行同样物理清(防残留向量命中已删图)
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
        List<Long> bodyImageIds = current == null ? new ArrayList<>() : versionImageMapper.findImageIdsByVersion(current.getId());
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

    /** 增/删正文插图（action=add/remove，均幂等）。图可来自全局图库。P1-⑦：读写关联表 sparkora_article_version_image。 */
    public void modifyBodyImage(Long projectId, Long imageId, String action) {
        ArticleVersionEntity v = requireVersionWithImages(projectId);
        boolean add;
        if ("add".equals(action)) add = true;
        else if ("remove".equals(action)) add = false;
        else throw new IllegalArgumentException("action 仅支持 add/remove");
        if (add && imageMapper.selectById(imageId) == null)
            throw new IllegalArgumentException("图片不存在");
        if (add) {
            // 幂等：已登记直接返回（不重排既有顺序）
            if (versionImageMapper.selectCount(new QueryWrapper<ArticleVersionImageEntity>()
                    .eq("version_id", v.getId()).eq("image_id", imageId)) > 0) return;
            ArticleVersionImageEntity e = new ArticleVersionImageEntity();
            e.setVersionId(v.getId());
            e.setImageId(imageId);
            Integer max = versionImageMapper.maxSortOrder(v.getId());
            e.setSortOrder(max == null ? 0 : max + 1);   // 追加到末尾（保序）
            e.setCreatedAt(LocalDateTime.now());
            try {
                versionImageMapper.insert(e);
            } catch (DuplicateKeyException ex) {
                // UNIQUE(version_id, image_id) 并发兜底：他请求已登记，视为成功
                log.debug("插图已登记(跳过) version={} image={}", v.getId(), imageId);
            }
        } else {
            versionImageMapper.deleteByVersionAndImage(v.getId(), imageId);   // 不存在也视为成功
        }
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
