package com.sparkora.qa.service;

import com.sparkora.car.service.CarRagService;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.dto.QaImageRef;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.service.ImageEmbeddingService;
import com.sparkora.service.ImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 问答答案配图解析服务（09-15 qa-auto-illustrate，子D）。
 *
 * 把「答案引用了哪些知识」延伸为「顺手给几张相关图」——两条来源路径：
 * <ol>
 *   <li><b>新闻关联图（便宜路径，始终执行）</b>：引用中 source=NEWS 的块带 {@code docId}
 *       （{@code sparkora_news_doc.id}）→ 内部 news_id → {@code sparkora_news.cover_image_id}
 *       → 图库资产。该路径**不产生额外 embedding 调用**（复用本轮检索结果，纯库内查询）。</li>
 *   <li><b>语义检索图（语义路径，仅图片意图触发）</b>：问题命中图片意图词时调子B
 *       {@link ImageEmbeddingService#searchImages}，支持「给我看销量海报」类问法。</li>
 * </ol>
 * 两路按 imageId 合并去重（新闻关联图优先——它与答案引用强相关），上限截断。
 *
 * <h3>硬约束</h3>
 * <ul>
 *   <li><b>绝不阻断答案生成</b>：本服务所有公开入口内部 try/catch，异常仅 warn 并返回空列表；
 *       {@code QaService} 调用处亦再包一层 try/catch。配图是附加展示，答案可用性优先。</li>
 *   <li><b>只读</b>：只做 select（news_doc / news / image_asset）与图片向量检索，
 *       不写任何用户内容（不插入正文、不改答案文本）；无批准流程（区别于子C）。</li>
 *   <li><b>不改检索语义</b>：不触碰 {@code searchTopKUnified} SQL 与配额，只消费其 citations。</li>
 * </ul>
 */
@Slf4j
@Service
public class QaImageRefService {

    private final NewsDocMapper newsDocMapper;
    private final NewsMapper newsMapper;
    private final ImageService imageService;
    private final ImageEmbeddingService embeddingService;
    private final AiProperties aiProps;

    public QaImageRefService(NewsDocMapper newsDocMapper, NewsMapper newsMapper,
                             ImageService imageService, ImageEmbeddingService embeddingService,
                             AiProperties aiProps) {
        this.newsDocMapper = newsDocMapper;
        this.newsMapper = newsMapper;
        this.imageService = imageService;
        this.embeddingService = embeddingService;
        this.aiProps = aiProps;
    }

    /**
     * 解析答案配图（合并两路，去重截断）。**永不抛出**——任何异常返回空列表 + warn。
     *
     * @param citations 本轮引用（{@code CarRagService.RagResult.citations()}，仅检索 OK 时非空；
     *                  此处不区分状态——空列表自然无图，无需额外分支）
     * @param question  用户本轮问题（用于图片意图判定与语义检索 query）
     * @param limit     图片条数上限（≤0 视为不出图）
     */
    public List<QaImageRef> forAnswer(List<CarRagService.Citation> citations, String question, int limit) {
        try {
            if (limit <= 0) return List.of();
            // 1) 新闻关联图（便宜路径，始终执行；无 NEWS 引用则自然为空）
            List<QaImageRef> newsRefs = byNewsCitations(citations, limit);
            // 2) 语义检索图（仅图片意图问法触发，避免每条问答都多一次 embedding 调用）
            List<QaImageRef> semRefs = List.of();
            if (QaImageIntent.isImageIntent(question) && newsRefs.size() < limit) {
                semRefs = bySemanticQuery(question, limit);
            }
            // 3) 合并去重：按 imageId 去重，新闻关联图优先（与答案引用强相关），截断至 limit
            Map<Long, QaImageRef> merged = new LinkedHashMap<>();
            for (QaImageRef r : newsRefs) {
                if (r != null && r.imageId() != null) merged.putIfAbsent(r.imageId(), r);
            }
            for (QaImageRef r : semRefs) {
                if (r != null && r.imageId() != null) merged.putIfAbsent(r.imageId(), r);
            }
            List<QaImageRef> out = new ArrayList<>(merged.values());
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } catch (Exception e) {
            log.warn("问答配图解析失败(忽略,答案优先): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 新闻关联图：NEWS 引用 → 该新闻的图库封面图。批量查询（避免 N+1），保引用相关度顺序（citations 按 score 降序）。
     *
     * 链路：{@code sparkora_news_doc.id}（docId）→ {@code news_id}（内部 BIGINT）
     * → {@code sparkora_news.id} → {@code cover_image_id} → 图库资产。
     *
     * 跳过项：非 NEWS 引用 / docId 为空 / 查无 news_doc / news 无 cover_image_id / 图库记录已删 / 图无 url。
     * 任一步失败仅 warn 并返回已成功部分（不抛出）。
     */
    public List<QaImageRef> byNewsCitations(List<CarRagService.Citation> citations, int limit) {
        if (citations == null || citations.isEmpty() || limit <= 0) return List.of();
        try {
            // 1) 过滤 NEWS + docId 非空，按引用顺序收集 docId（去重保序）
            LinkedHashSet<Long> docIds = new LinkedHashSet<>();
            for (CarRagService.Citation c : citations) {
                if (c == null || !"NEWS".equals(c.source()) || c.docId() == null) continue;
                docIds.add(c.docId());
            }
            if (docIds.isEmpty()) return List.of();

            // 2) 批量取切块 → 内部 news_id（按 docId 保序去重）
            List<NewsDocEntity> docs = newsDocMapper.selectBatchIds(docIds);
            if (docs == null || docs.isEmpty()) return List.of();
            Map<Long, NewsDocEntity> docById = new LinkedHashMap<>();
            for (NewsDocEntity d : docs) {
                if (d != null && d.getId() != null) docById.put(d.getId(), d);
            }
            LinkedHashSet<Long> newsIds = new LinkedHashSet<>();
            for (Long docId : docIds) {                       // 按 citations 顺序遍历 → 保相关度顺序
                NewsDocEntity d = docById.get(docId);
                if (d != null && d.getNewsId() != null) newsIds.add(d.getNewsId());
            }
            if (newsIds.isEmpty()) return List.of();

            // 3) 批量取新闻 → cover_image_id + 标题（跳过无封面）
            List<NewsEntity> newsList = newsMapper.selectBatchIds(newsIds);
            if (newsList == null || newsList.isEmpty()) return List.of();
            Map<Long, NewsEntity> newsById = new LinkedHashMap<>();
            for (NewsEntity n : newsList) {
                if (n != null && n.getId() != null) newsById.put(n.getId(), n);
            }
            // 新闻顺序 = newsIds 顺序（citations 相关度），封面 id 去重保序
            LinkedHashSet<Long> imageIds = new LinkedHashSet<>();
            Map<Long, NewsEntity> newsByImageId = new LinkedHashMap<>();
            for (Long newsId : newsIds) {
                NewsEntity n = newsById.get(newsId);
                if (n == null || n.getCoverImageId() == null) continue;   // 无封面 → 跳过该条
                imageIds.add(n.getCoverImageId());
                newsByImageId.putIfAbsent(n.getCoverImageId(), n);
            }
            if (imageIds.isEmpty()) return List.of();

            // 4) 批量取图库资产 + 派生 url/thumbUrl；过滤 url 为空（图已删/未转存）
            List<ImageAssetEntity> images = imageService.loadDerived(new ArrayList<>(imageIds));
            Map<Long, ImageAssetEntity> imgById = new LinkedHashMap<>();
            for (ImageAssetEntity img : images) {
                if (img != null && img.getId() != null) imgById.put(img.getId(), img);
            }
            List<QaImageRef> out = new ArrayList<>();
            for (Long imageId : imageIds) {
                if (out.size() >= limit) break;
                ImageAssetEntity img = imgById.get(imageId);
                if (img == null || img.getUrl() == null || img.getUrl().isBlank()) continue;   // 已删/无 url → 跳过
                NewsEntity n = newsByImageId.get(imageId);
                out.add(new QaImageRef(img.getId(), img.getUrl(), img.getThumbUrl(),
                        n == null ? null : n.getTitle(),
                        n == null ? null : n.getNewsId(),
                        img.getSource()));
            }
            return out;
        } catch (Exception e) {
            log.warn("问答配图(新闻关联)解析失败(忽略): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 语义检索图：图片意图问题 → 子B 图片向量检索。失败仅 warn 返回空列表。
     * query 清洗：剥离意图短语（「给我看」/「海报」…）后若剩余为空则用原问题。
     */
    public List<QaImageRef> bySemanticQuery(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) return List.of();
        try {
            String query = QaImageIntent.cleanQuery(question);
            if (query == null || query.isBlank()) return List.of();
            List<ImageSearchHit> hits = embeddingService.searchImages(query, limit, aiProps.getImageMinScore(), null);
            if (hits == null || hits.isEmpty()) return List.of();
            List<QaImageRef> out = new ArrayList<>(Math.min(hits.size(), limit));
            for (ImageSearchHit h : hits) {
                if (h == null || h.imageId() == null) continue;
                if (h.url() == null || h.url().isBlank()) continue;   // 无图床 url 的命中不回传（预览必须用原图）
                out.add(new QaImageRef(h.imageId(), h.url(), h.thumbUrl(),
                        semanticTitle(h), null, h.source()));
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("问答配图(语义检索)失败(忽略): {}", e.getMessage());
            return List.of();
        }
    }

    /** 语义图展示标题：嵌入原文首段（截断）→ 文件名 → 空串。 */
    private static String semanticTitle(ImageSearchHit h) {
        String src = h.sourceText();
        if (src != null && !src.isBlank()) {
            String t = src.strip();
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(0, nl);
            return t.length() > 60 ? t.substring(0, 60) : t;
        }
        return h.fileName() == null ? "" : h.fileName();
    }
}
