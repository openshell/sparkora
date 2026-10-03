package com.sparkora.news.service;

import com.sparkora.ai.EmbedStats;
import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.TextChunker;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 新闻切块 + 向量化服务(C2,仿 CarChunkService / KbDocService)。
 *
 * 流程:先物理清旧块+向量,再切块(首行「新闻：<title>（<publishDate>）」)→ 逐块 embedding 入库。
 * embedding 并发化(固定小线程池)+ 单块失败重试 1 次;失败块 warn 计数,不静默。
 * 空正文(图片型新闻)跳过切块(仅元数据入库);若标题存在则保留单块标题锚点,便于按标题检索。
 * 切块算法委托 {@link TextChunker}(09-27 知识域写入侧统一)。
 *
 * 事务边界(09-27 统一为 IMAGE 范式):embedding 网络调用在事务外,向量写入经自注入代理走
 * {@code REQUIRES_NEW} 独立事务——修复此前 {@code @Transactional insertDocWithEmbedding} 同类直调失效
 * (线程池 lambda 内 this 调用绕过代理),失败回滚不留孤儿块,且不污染调用方 NewsService.upsertOne 事务。
 */
@Slf4j
@Service
public class NewsDocService {

    private final NewsMapper            newsMapper;
    private final NewsDocMapper         docMapper;
    private final EmbeddingClient       embeddingClient;
    private final EmbeddingBatchRunner batchRunner;
    /** 自注入代理（@Lazy）：让 {@link #persistNewsDoc} 的 REQUIRES_NEW 事务真的生效（this 调用不走代理）。 */
    @Autowired
    @Lazy
    private NewsDocService self;
    /** 单表 store（10-03 E1）；字段注入可选，单测直接 new 时为 null（同步守卫降级）。 */
    @Autowired(required = false)
    private com.sparkora.ai.vector.VectorStoreService vectorStoreService;

    public NewsDocService(NewsMapper newsMapper, NewsDocMapper docMapper,
                          EmbeddingClient embeddingClient,
                          EmbeddingBatchRunner batchRunner) {
        this.newsMapper = newsMapper;
        this.docMapper = docMapper;
        this.embeddingClient = embeddingClient;
        this.batchRunner = batchRunner;
    }

    /** 重建某新闻的全部切块 + 向量(先清后建,幂等)。返回 total/success/failed 计数(R6 手动重建端点用)。 */
    public EmbedStats rebuildForNews(Long newsId) {
        deleteByNews(newsId);
        NewsEntity n = newsMapper.selectById(newsId);
        if (n == null) return new EmbedStats(0, 0, 0);
        List<String> chunks = chunkContent(n.getTitle(), n.getPublishDate(), n.getContent());
        if (chunks.isEmpty()) {
            log.info("新闻无正文且无标题,跳过切块 newsId={}", newsId);
            return new EmbedStats(0, 0, 0);
        }
        List<NewsDocEntity> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            NewsDocEntity d = new NewsDocEntity();
            d.setNewsId(newsId);
            d.setSeq(i);
            d.setChunkType(chunkTypeOf(chunks));
            d.setChunkText(chunks.get(i));
            d.setNewsTitle(n.getTitle());   // 10-03 E1:store metadata.name
            docs.add(d);
        }
        // embedding 并发化(固定小线程池,不随新闻数膨胀)+ 单块失败重试 1 次
        return batchRunner.run(docs, NewsDocEntity::getChunkText,
                (d, vec) -> (self == null ? this : self).persistNewsDoc(d, vec),
                "newsId=" + newsId, 4, 1);
    }

    /** 物理清块与向量(重建/删除共用)。10-03 E1:同步删除单表 store 行(先读块 id 再删)。 */
    @Transactional
    public void deleteByNews(Long newsId) {
        java.util.List<NewsDocEntity> docs = docMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NewsDocEntity>()
                        .eq("news_id", newsId));
        java.util.List<Long> docIds = new java.util.ArrayList<>();
        for (NewsDocEntity d : docs) if (d.getId() != null) docIds.add(d.getId());
        docMapper.deleteByNewsId(newsId);
        if (vectorStoreService != null && !docIds.isEmpty()) {
            vectorStoreService.deleteByRef(com.sparkora.ai.vector.VectorDomain.NEWS.name(), docIds);
        }
    }

    /**
     * 持久化文档块 + 向量(先插 doc 拿 id,再插 embedding)——独立事务边界,
     * 见类注释的事务隔离说明。失败则 doc 与向量一并回滚(不留孤儿块)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistNewsDoc(NewsDocEntity doc, String vec) {
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        docMapper.insert(doc);
        // 10-03 E6:旧向量表已退役,只写单表 store
        if (vectorStoreService != null) {
            vectorStoreService.upsert(com.sparkora.ai.vector.VectorDomain.NEWS.name(), doc.getId(), null,
                    doc.getChunkType(), doc.getNewsTitle(), true, embeddingClient.modelName(),
                    doc.getChunkText(), vec);
        }
    }

    /**
     * 块类型:仅当整篇只产出一个块且该块无换行(即纯标题锚点块,正文为空)时为 NEWS_TITLE,其余为 NEWS_BODY。
     * 提取为纯函数便于单测覆盖两分支。
     */
    static String chunkTypeOf(List<String> chunks) {
        return chunks.size() == 1 && isTitleOnly(chunks.get(0)) ? "NEWS_TITLE" : "NEWS_BODY";
    }

    private static boolean isTitleOnly(String chunk) {
        return chunk != null && chunk.indexOf('\n') < 0;
    }

    /**
     * 切块(薄委托 {@link TextChunker},09-27 统一实现):
     * 首行固定「新闻:<title>(<publishDate>)」;正文为空时仅标题非空才保留标题块(NEWS 语义);
     * 超长段按句读切分合并至 ≤500。
     * 10-03 E2:显式启用 {@link TextChunker#DEFAULT_OVERLAP_CHARS} 滑动重叠(减少跨块边界语义切断)。
     */
    static List<String> chunkContent(String title, LocalDateTime publishDate, String content) {
        String header = "新闻：" + (title == null ? "" : title.trim())
                + "（" + (publishDate == null ? "" : publishDate.toLocalDate().toString()) + "）";
        boolean titlePresent = title != null && !title.isBlank();
        return TextChunker.chunk(header, content, titlePresent, false, TextChunker.NEWS_SEPARATORS,
                TextChunker.DEFAULT_OVERLAP_CHARS);
    }
}
