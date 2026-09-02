package com.sparkora.car.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.mapper.CarDocEmbeddingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务。供文章生成(BriefService/VersionService)与内部问答共用。
 *
 * 流程:query → embedding → pgvector 余弦相似度 top-K → 组装知识上下文。
 * 检索结果作为事实约束注入 prompt,衔接 fact_risks 防编造。
 */
@Slf4j
@Service
public class CarRagService {

    private final CarDocEmbeddingMapper embMapper;
    private final EmbeddingClient embeddingClient;

    public CarRagService(CarDocEmbeddingMapper embMapper, EmbeddingClient embeddingClient) {
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
    }

    /** 检索结果项。 */
    public record Hit(String chunkText, double score) {}

    /**
     * 对某车型检索 top-K 文档块。
     * @param modelId 车型 id
     * @param query   查询文本(如"大唐EV 纯电续航")
     * @param topK    返回条数
     * @return 命中的文档块文本列表(按相似度降序)
     */
    public List<Hit> retrieve(Long modelId, String query, int topK) {
        if (modelId == null || query == null || query.isBlank()) return List.of();
        String vec = embeddingClient.embed(query);
        List<Map<String, Object>> rows = embMapper.searchTopK(modelId, vec, topK);
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String text = row.get("chunkText") == null ? "" : String.valueOf(row.get("chunkText"));
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            hits.add(new Hit(text, score));
        }
        return hits;
    }

    /**
     * 组装知识上下文(注入 prompt 用)。带相似度阈值过滤,低于阈值视为无相关数据。
     * @return 知识上下文文本;无命中返回空串
     */
    public String buildContext(Long modelId, String query, int topK, double minScore) {
        List<Hit> hits = retrieve(modelId, query, topK);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            if (h.score() < minScore) continue;
            sb.append(h.chunkText()).append("\n---\n");
        }
        return sb.toString();
    }
}
