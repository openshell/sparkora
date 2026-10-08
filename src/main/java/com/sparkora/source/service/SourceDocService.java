package com.sparkora.source.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.ai.EmbedStats;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.TextChunker;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用信源切块 + 向量化服务（10-05-source-domain-retrieval E）。
 *
 * <p>把 B 侧采集入库到 {@code sparkora_news} 的通用信源内容（{@code source_id != NULL}、正文非空）
 * 切块并嵌入到单表 {@code vector_store}（{@code domain=NEWS}，{@code refId=sparkora_news_doc.id}）。
 * 与 {@link com.sparkora.news.service.NewsDocService}（BYD 路径）**同构**：复用同一 id 空间、同一
 * {@link TextChunker}、同一 {@link EmbeddingBatchRunner}、同一事务范式，区别仅在：
 * <ul>
 *   <li>首行锚点「信源：&lt;title&gt;（&lt;publishDate&gt;）」；</li>
 *   <li>结构化分类（销量数据/投诉榜/政策公示）用 {@link TextChunker} 的 {@code preserveNewlines=true}
 *       保留表格行/列结构（父 design §6）；</li>
 *   <li>store metadata 写 {@code sourceType}/{@code category}/{@code publishDate}（NEWS 域内二级隔离依据）。</li>
 * </ul>
 *
 * <p><b>不改 {@code domain} 名</b>（仍 {@code NEWS}）——{@code VectorStoreService.docId} 用
 * {@code UUID(domain+":"+refId)} 确定性主键，改名会使存量 NEWS 向量 id 失配须全量重嵌（design §6）。
 *
 * <p>事务边界照 {@code NewsDocService}：embedding 网络调用在事务外，向量写入经 {@code @Lazy} 自注入
 * 代理走 {@code REQUIRES_NEW} 独立事务；单块失败 warn 计数不阻断。
 */
@Slf4j
@Service
public class SourceDocService {

    private final NewsMapper newsMapper;
    private final NewsDocMapper docMapper;
    private final SourceMapper sourceMapper;
    private final SourceChannelMapper channelMapper;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingBatchRunner batchRunner;

    /** 自注入代理（@Lazy）：让 {@link #persistSourceDoc} 的 REQUIRES_NEW 事务真的生效（this 调用不走代理）。 */
    @Autowired
    @Lazy
    private SourceDocService self;

    /** 单表 store；字段注入可选，单测直接 new 时为 null（降级跳过）。 */
    @Autowired(required = false)
    private com.sparkora.ai.vector.VectorStoreService vectorStoreService;

    public SourceDocService(NewsMapper newsMapper, NewsDocMapper docMapper,
                            SourceMapper sourceMapper, SourceChannelMapper channelMapper,
                            EmbeddingClient embeddingClient, EmbeddingBatchRunner batchRunner) {
        this.newsMapper = newsMapper;
        this.docMapper = docMapper;
        this.sourceMapper = sourceMapper;
        this.channelMapper = channelMapper;
        this.embeddingClient = embeddingClient;
        this.batchRunner = batchRunner;
    }

    /**
     * 重建某通用信源内容（{@code sparkora_news} 行）的全部切块 + 向量（先清后建，幂等）。
     * BYD 行（{@code source_id == NULL}）直接跳过——由 {@code NewsDocService} 负责。
     * 采集链路 upsert 后调用（AC-E1 真实入库路径）；单条失败 warn 不阻断。
     */
    public EmbedStats rebuildForNews(Long newsId) {
        NewsEntity n = newsMapper.selectById(newsId);
        if (n == null || n.getSourceId() == null) return new EmbedStats(0, 0, 0);
        return rebuild(n);
    }

    private EmbedStats rebuild(NewsEntity n) {
        Long newsId = n.getId();
        deleteByNews(newsId);
        SourceChannelEntity channel = n.getChannelId() == null ? null : channelMapper.selectById(n.getChannelId());
        SourceEntity source = n.getSourceId() == null ? null : sourceMapper.selectById(n.getSourceId());
        String sourceType = SourceCatalog.sourceTypeOf(source == null ? null : source.getName(),
                channel == null ? null : channel.getName());
        String category = n.getCategory() != null ? n.getCategory()
                : (channel == null ? null : channel.getCategory());
        List<String> chunks = chunkContent(n.getTitle(), n.getPublishDate(), n.getContent(), category);
        if (chunks.isEmpty()) {
            log.info("通用信源无正文且无标题,跳过切块 newsId={}", newsId);
            return new EmbedStats(0, 0, 0);
        }
        List<NewsDocEntity> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            NewsDocEntity d = new NewsDocEntity();
            d.setNewsId(newsId);
            d.setSeq(i);
            d.setChunkType(chunkTypeOf(chunks));
            d.setChunkText(chunks.get(i));
            d.setNewsTitle(n.getTitle());
            d.setPublishDate(n.getPublishDate());
            d.setSourceType(sourceType);
            d.setCategory(category);
            docs.add(d);
        }
        return batchRunner.run(docs, NewsDocEntity::getChunkText,
                (d, vec) -> (self == null ? this : self).persistSourceDoc(d, vec),
                "sourceNewsId=" + newsId, 4, 1);
    }

    /** 物理清块与 store 向量（重建/删除共用）。 */
    @Transactional
    public void deleteByNews(Long newsId) {
        List<NewsDocEntity> docs = docMapper.selectList(
                new QueryWrapper<NewsDocEntity>().eq("news_id", newsId));
        List<Long> docIds = new ArrayList<>();
        for (NewsDocEntity d : docs) if (d.getId() != null) docIds.add(d.getId());
        docMapper.deleteByNewsId(newsId);
        if (vectorStoreService != null && !docIds.isEmpty()) {
            vectorStoreService.deleteByRef(com.sparkora.ai.vector.VectorDomain.NEWS.name(), docIds);
        }
    }

    /**
     * 持久化文档块 + 向量（先插 doc 拿 id，再写 store）——独立事务边界，见类注释。
     * metadata 写 {@code sourceType}/{@code category}/{@code publishDate}，支撑检索层 NEWS 域内二级隔离。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistSourceDoc(NewsDocEntity doc, String vec) {
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(doc);
        if (vectorStoreService != null) {
            vectorStoreService.upsert(com.sparkora.ai.vector.VectorDomain.NEWS.name(), doc.getId(), null,
                    doc.getChunkType(), doc.getNewsTitle(), true, embeddingClient.modelName(),
                    doc.getChunkText(), vec, sourceMeta(doc));
        }
    }

    /** 通用信源 store 扩展 metadata（空值不写）。 */
    static Map<String, Object> sourceMeta(NewsDocEntity doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (doc.getSourceType() != null) m.put("sourceType", doc.getSourceType());
        if (doc.getCategory() != null) m.put("category", doc.getCategory());
        if (doc.getPublishDate() != null) m.put("publishDate", doc.getPublishDate().toLocalDate().toString());
        return m;
    }

    /**
     * 块类型：整篇仅一个无换行块（空正文标题锚点）→ {@code NEWS_TITLE}，其余 {@code NEWS_BODY}
     * （与 BYD 路径一致，检索/引用语义统一）。
     */
    static String chunkTypeOf(List<String> chunks) {
        return chunks.size() == 1 && isTitleOnly(chunks.get(0)) ? "NEWS_TITLE" : "NEWS_BODY";
    }

    private static boolean isTitleOnly(String chunk) {
        return chunk != null && chunk.indexOf('\n') < 0;
    }

    /**
     * 切块（薄委托 {@link TextChunker}）：首行「信源：&lt;title&gt;（&lt;publishDate&gt;）」；
     * 结构化分类（销量数据/投诉榜/政策公示）启用 {@code preserveNewlines=true}（表格行/列不丢），
     * 其余保持默认 false（回归锁）；滑动重叠 {@link TextChunker#DEFAULT_OVERLAP_CHARS}。
     */
    static List<String> chunkContent(String title, LocalDateTime publishDate, String content, String category) {
        String header = "信源：" + (title == null ? "" : title.trim())
                + "（" + (publishDate == null ? "" : publishDate.toLocalDate().toString()) + "）";
        boolean titlePresent = title != null && !title.isBlank();
        boolean preserve = SourceCatalog.isStructured(category);
        return TextChunker.chunk(header, content, titlePresent, false, TextChunker.NEWS_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS, preserve);
    }
}
