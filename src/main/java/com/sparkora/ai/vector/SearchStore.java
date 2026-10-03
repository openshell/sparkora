package com.sparkora.ai.vector;

import org.springframework.ai.document.Document;

import java.util.Collection;
import java.util.List;

/**
 * 向量检索读取抽象（10-03 E1 阶段 A）。
 *
 * <p>把 {@code CarRagService} / {@code ImageEmbeddingService} 的「存储 + 相似度检索」依赖
 * 收敛到本接口，便于单测以手写假件替换（沿用既有的「不 Mockito 代理 mapper」风格）。
 * 生命周期写入（upsert/setActive/deleteByRef）不在读取抽象内，由写路径依赖具体
 * {@link VectorStoreService}。
 */
public interface SearchStore {

    /**
     * 按域检索（候选窗口隔离由调用方决定：CAR+KB 合并一次、NEWS 独立一次）。
     * 过滤固定为 {@code domain ∈ domains && active == true && embeddingModel == 当前模型}。
     *
     * @param similarityThreshold 0 = ACCEPT_ALL（门槛在 Java 侧判，保持旧路径逐条对拍一致）
     */
    List<Document> searchDomains(Collection<String> domains, String query, int topK,
                                 double similarityThreshold, String embeddingModel);

    /** 单车型 CAR 域检索（对齐旧 {@code CarDocEmbeddingMapper.searchTopK} 语义：model_id 过滤）。 */
    List<Document> searchByModel(Long modelId, String query, int topK,
                                 double similarityThreshold, String embeddingModel);

    /** 图片域检索（独立入口）。{@code refIds} 非空时限定候选集（标签 AND 预过滤白名单）。 */
    List<Document> searchImages(String query, Collection<Long> refIds, double minScore,
                                int topK, String embeddingModel);
}
