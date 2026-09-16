package com.sparkora.service;

import com.sparkora.car.client.EmbeddingClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.config.AiProperties;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.image.embed.ImageEmbeddingTextBuilder;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.mapper.ImageEmbeddingMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.storage.ImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片语义向量服务（09-15 img-semantic-search，子B）。
 *
 * 让图片可被**自然语言检索**（「销量海报」「出海签约的照片」）——图片本身无可嵌入文本，
 * 用描述性文本代理（{@link ImageEmbeddingTextBuilder}：新闻标题 + 标签 / AI prompt / 文件名）向量化，
 * 复用现有 {@link EmbeddingClient}（Qwen3-Embedding-8B / 1024 维），与 car/kb/news 三域**同一向量空间**。
 *
 * 写路径（对标 KbDocService 的 rebuild 范式）：
 *  - 增量：{@code ImageService.persistOrReuse} 入库后调 {@link #embedQuietly(Long)}（best-effort，失败仅 warn，
 *    图片可用性优先于可检索性）；
 *  - 存量/重建：{@link #rebuildAll()}（全量）/ {@link #rebuildMissing()}（仅补缺失，启动 runner 用），
 *    逐图先物理删旧向量再插（幂等）。
 * 读路径：{@link #searchImages}——标签 AND 预过滤缩候选 → 查询向量 → HNSW cosine top-K → 回填展示字段。
 *
 * 已知限制：标签变更不触发重嵌（嵌入文本会与当前标签漂移）；用重建接口修正即可（本期不做实时）。
 */
@Slf4j
@Service
public class ImageEmbeddingService {

    /** 单次检索返回条数默认值。 */
    static final int DEFAULT_TOPK = 10;
    /** 单次检索返回条数上限（超出收敛，不报错）。 */
    static final int MAX_TOPK = 50;
    /** 标签预过滤候选集截断保底（与 ImageService.list 的 tag 筛选口径一致）。 */
    static final int MAX_TAG_CANDIDATES = 500;

    private final ImageEmbeddingMapper embMapper;
    private final ImageAssetMapper imageMapper;
    private final EmbeddingClient embeddingClient;
    private final ImageTagService tagService;
    private final ImageStorage imageStorage;
    private final AiProperties aiProps;
    /** 七牛配置（可选注入：图床供应商非七牛时 bean 不存在，thumbUrl 降级为原图 url）。 */
    private final ObjectProvider<QiniuProperties> qiniuProps;
    /** 新闻主表 mapper（byd-news 图嵌入文本需反查来源新闻标题；新闻域缺失时退化为只用标签）。 */
    private final ObjectProvider<NewsMapper> newsMapper;
    /** 自注入代理（@Lazy）：让 {@link #persistVector} 的 REQUIRES_NEW 事务真的生效（this 调用不走代理）。 */
    @Autowired
    @Lazy
    private ImageEmbeddingService self;

    public ImageEmbeddingService(ImageEmbeddingMapper embMapper, ImageAssetMapper imageMapper,
                                 EmbeddingClient embeddingClient, ImageTagService tagService,
                                 ImageStorage imageStorage, AiProperties aiProps,
                                 ObjectProvider<QiniuProperties> qiniuProps,
                                 ObjectProvider<NewsMapper> newsMapper) {
        this.embMapper = embMapper;
        this.imageMapper = imageMapper;
        this.embeddingClient = embeddingClient;
        this.tagService = tagService;
        this.imageStorage = imageStorage;
        this.aiProps = aiProps;
        this.qiniuProps = qiniuProps;
        this.newsMapper = newsMapper;
    }

    /** 向量化结果（可观测）：成功/失败图计数（参照 KB 域 EmbedStats 先例）。 */
    public record EmbedStats(int total, int success, int failed) {}

    // ==================== 写路径 ====================

    /**
     * 单图向量化（重建/增量共用）：构造嵌入文本 → embed → **先物理删旧向量再插**（幂等）。
     * 失败抛异常（由调用方决定吞或计数）；先清后插保证重跑结果稳定、无重复行。
     *
     * **事务隔离（关键）**：向量写入经自注入代理走 {@code REQUIRES_NEW}，**绝不加入调用方的环境事务**：
     * <ul>
     *   <li>入库链路（新闻同步 / 车型同步）可能是事务性的。若向量 SQL 在其中失败（维度不符 /
     *       唯一索引并发冲突），PostgreSQL 会把**整个调用方事务**置为 aborted——此后调用方任何 SQL 都抛
     *       「current transaction is aborted」，Java 侧 catch 无法挽回，「嵌入失败不阻断图片入库」的契约即被打破
     *       （图片 INSERT 虽已执行，仍会随事务回滚）。</li>
     *   <li>独立事务还让「先删后插」**原子化**：重嵌失败时回滚，保留旧向量而非留下「删了没插上」的空洞
     *       （旧向量好过无向量；确需刷新可事后调重建接口）。</li>
     * </ul>
     * embedding 网络调用放在事务之外（不长时间占事务/连接）。
     */
    public void embedOne(ImageAssetEntity img) {
        if (img == null || img.getId() == null) throw new IllegalArgumentException("图片不存在");
        String text = buildText(img);
        String vec = embeddingClient.embed(text);   // 网络调用放在事务之外
        if (self != null) {
            self.persistVector(img.getId(), vec, text);   // 独立事务（生产路径）
        } else {
            persistVectorInline(img.getId(), vec, text);  // 单测直接 new 时无代理：退化为直写
        }
    }

    /** 向量写入（先物理删旧行再插）——独立事务边界，见 {@link #embedOne} 的事务隔离说明。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistVector(Long imageId, String vec, String text) {
        persistVectorInline(imageId, vec, text);
    }

    private void persistVectorInline(Long imageId, String vec, String text) {
        embMapper.deleteByImageId(imageId);
        embMapper.insert(imageId, vec, text);
    }

    /**
     * 入库后 best-effort 嵌入：**捕获全部异常仅 warn，绝不向上抛**——
     * 图片可入库优先于可检索（缺失向量可由 {@link #rebuildAll()} / {@link #rebuildMissing()} 补齐）。
     * 唯一索引并发冲突（两请求同时嵌入同一图）也在此吞掉并告警。
     */
    public void embedQuietly(Long imageId) {
        if (imageId == null) return;
        try {
            ImageAssetEntity img = imageMapper.selectById(imageId);
            if (img == null) {
                log.warn("图片向量化跳过(图片不存在) id={}", imageId);
                return;
            }
            embedOne(img);
            log.info("图片向量化完成 id={} source={}", imageId, img.getSource());
        } catch (Exception e) {
            log.warn("图片向量化失败(不影响入库,可用重建接口补齐) id={}: {}", imageId, e.getMessage());
        }
    }

    /** 全量重建（先清后插，幂等）：逐图 {@link #embedOne}，单图失败跳过并计数。 */
    public EmbedStats rebuildAll() {
        List<ImageAssetEntity> all = imageMapper.selectList(
                new QueryWrapper<ImageAssetEntity>().orderByAsc("id"));
        return rebuild(all);
    }

    /** 仅补缺失：只处理无向量的图（LEFT JOIN 差集），启动 runner 用；重跑无缺失即零副作用。 */
    public EmbedStats rebuildMissing() {
        List<Long> missing = embMapper.findImageIdsWithoutEmbedding();
        if (missing == null || missing.isEmpty()) return new EmbedStats(0, 0, 0);
        List<ImageAssetEntity> imgs = imageMapper.selectBatchIds(missing);
        // selectBatchIds 不保证顺序（且为 IN 查询），按 id 升序处理便于日志对照
        imgs.sort(java.util.Comparator.comparing(ImageAssetEntity::getId));
        return rebuild(imgs);
    }

    /** 逐图重建的公共实现：单图失败 warn 跳过，不阻断整体。 */
    private EmbedStats rebuild(List<ImageAssetEntity> images) {
        List<ImageAssetEntity> list = images == null ? List.of() : images;
        int ok = 0, fail = 0;
        for (ImageAssetEntity img : list) {
            try {
                embedOne(img);
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("图片向量重建失败(跳过) id={} source={} file={}: {}",
                        img.getId(), img.getSource(), img.getFileName(), e.getMessage());
            }
        }
        if (fail > 0) {
            log.warn("图片向量重建完成(有缺失) 成功 {}/{} 失败 {}", ok, list.size(), fail);
        } else if (!list.isEmpty()) {
            log.info("图片向量重建完成 成功 {}/{}", ok, list.size());
        }
        return new EmbedStats(list.size(), ok, fail);
    }

    /** 删图联动（ImageService.delete 调用）：物理清该图向量（关系行生命周期 = 图片生命周期）。 */
    public void deleteByImageId(Long imageId) {
        if (imageId == null) return;
        int n = embMapper.deleteByImageId(imageId);
        if (n > 0) log.debug("删除图片向量 image={} rows={}", imageId, n);
    }

    /**
     * 构造某图的嵌入文本（按来源分派，见 {@link ImageEmbeddingTextBuilder}）：
     * byd-news 需反查来源新闻标题（source_ref = 官方 news_id，单次 selectOne；查不到退化为只用标签）。
     * 重建路径自给自足——无需调用方补上下文。
     */
    private String buildText(ImageAssetEntity img) {
        List<String> tags = tagService.tagNamesOf(img.getId());
        String newsTitle = newsTitleOf(img);
        return ImageEmbeddingTextBuilder.build(img, tags, newsTitle);
    }

    /** byd-news 且 source_ref 非空时反查来源新闻标题；其他来源/查不到返回 null。 */
    private String newsTitleOf(ImageAssetEntity img) {
        if (!"byd-news".equals(img.getSource())) return null;
        String ref = img.getSourceRef();
        if (ref == null || ref.isBlank()) return null;
        NewsMapper nm = newsMapper.getIfAvailable();
        if (nm == null) return null;
        try {
            NewsEntity n = nm.selectOne(new QueryWrapper<NewsEntity>().eq("news_id", ref).last("LIMIT 1"));
            return n == null ? null : n.getTitle();
        } catch (Exception e) {
            log.warn("反查新闻标题失败(退化为只用标签) image={} ref={}: {}", img.getId(), ref, e.getMessage());
            return null;
        }
    }

    // ==================== 读路径（语义检索） ====================

    /**
     * 图片语义检索：标签 AND 预过滤（可选）→ 查询向量 → HNSW cosine top-K → 回填展示字段。
     *
     * @param query    自然语言检索文本（必填非空，否则 IllegalArgumentException → 控制器 400）
     * @param topK     返回条数（null/&lt;1 → {@value #DEFAULT_TOPK}；&gt;{@value #MAX_TOPK} 收敛，均不报错）
     * @param minScore 相似度门槛（null → {@code AI_IMAGE_MIN_SCORE}，默认 0.3）
     * @param tags     标签预过滤（AND 语义，与 {@code GET /api/images} 一致；空/null = 不筛）
     * @return 按相似度降序的命中列表；标签交集为空时返回空列表且**不调用 embedding**
     */
    public List<ImageSearchHit> searchImages(String query, Integer topK, Double minScore, List<String> tags) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("检索内容不能为空");
        int limit = normalizeTopK(topK);
        double threshold = minScore == null ? aiProps.getImageMinScore() : minScore;

        // 标签预过滤：复用图库列表的 AND 交集语义（resolveTagIds，同包 package-private 静态方法）
        // 返回 null = 未指定标签；返回空集 = 无图同时命中全部标签 → 直接返回，省一次 embedding 调用
        java.util.Set<Long> tagIds = ImageService.resolveTagIds(tags, tagService::imageIdsByTag);
        if (tagIds != null && tagIds.isEmpty()) return List.of();
        List<Long> idWhiteList = null;
        if (tagIds != null) {
            idWhiteList = tagIds.size() > MAX_TAG_CANDIDATES
                    ? new ArrayList<>(tagIds).subList(0, MAX_TAG_CANDIDATES)   // 命中集截断保底(同图库列表)
                    : new ArrayList<>(tagIds);
        }

        String queryVec = embeddingClient.embed(query.trim());
        List<Map<String, Object>> rows = embMapper.searchTopK(queryVec, idWhiteList, threshold, limit);
        if (rows == null || rows.isEmpty()) return List.of();

        // 批查主表回填展示字段（避免 N+1，也避免向量表 JOIN 主表）
        Map<Long, ImageAssetEntity> byId = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long imageId = row.get("imageId") == null ? null : ((Number) row.get("imageId")).longValue();
            if (imageId != null) ids.add(imageId);
        }
        if (ids.isEmpty()) return List.of();
        for (ImageAssetEntity img : imageMapper.selectBatchIds(ids)) byId.put(img.getId(), img);

        QiniuProperties q = qiniuProps.getIfAvailable();
        List<ImageAssetEntity> images = new ArrayList<>();
        List<ImageSearchHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long imageId = row.get("imageId") == null ? null : ((Number) row.get("imageId")).longValue();
            ImageAssetEntity img = imageId == null ? null : byId.get(imageId);
            if (img == null) continue;   // 图已物理删除但向量残留（极端竞态）：跳过
            ImageService.fillDerived(img, imageStorage, q);
            images.add(img);
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            String sourceText = row.get("sourceText") == null ? null : String.valueOf(row.get("sourceText"));
            hits.add(new ImageSearchHit(img.getId(), score, sourceText, img.getFileName(), img.getSource(),
                    img.getSourceRef(), img.getUrl(), img.getThumbUrl(), List.of()));
        }
        // 页内批查回填标签（ImageSearchHit 为 record，不可变：按 id 取回后重建条目）
        if (!images.isEmpty()) {
            tagService.fillTags(images);
            Map<Long, List<String>> tagsById = new LinkedHashMap<>();
            for (ImageAssetEntity img : images) tagsById.put(img.getId(), img.getTags());
            List<ImageSearchHit> withTags = new ArrayList<>(hits.size());
            for (ImageSearchHit h : hits) {
                withTags.add(new ImageSearchHit(h.imageId(), h.score(), h.sourceText(), h.fileName(), h.source(),
                        h.sourceRef(), h.url(), h.thumbUrl(),
                        tagsById.getOrDefault(h.imageId(), List.of())));
            }
            hits = withTags;
        }
        return hits;
    }

    /** topK 收敛（纯函数，可单测）：null/&lt;1 → 默认 10；&gt;50 → 50；否则原值。 */
    static int normalizeTopK(Integer topK) {
        if (topK == null || topK < 1) return DEFAULT_TOPK;
        return Math.min(topK, MAX_TOPK);
    }
}
