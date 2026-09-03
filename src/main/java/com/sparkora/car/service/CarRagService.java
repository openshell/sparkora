package com.sparkora.car.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
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
    private final AiProperties aiProps;

    public CarRagService(CarDocEmbeddingMapper embMapper, EmbeddingClient embeddingClient, AiProperties aiProps) {
        this.embMapper = embMapper;
        this.embeddingClient = embeddingClient;
        this.aiProps = aiProps;
    }

    /** 检索结果项。 */
    public record Hit(String chunkText, double score) {}

    /** 知识库检索状态:OK 命中并过门槛 / LOW_CONFIDENCE 整体置信度过低已抛弃 / FAILED 检索异常降级 / NO_KNOWLEDGE 无车型对象或无命中。 */
    public enum RagStatus { OK, LOW_CONFIDENCE, FAILED, NO_KNOWLEDGE }

    /**
     * 检索结果(供生成链路「必查+降级可见」使用)。
     * @param status    检索状态
     * @param context   注入 prompt 的知识上下文文本(抛弃/失败/无命中时为空串)
     * @param hitCount  通过逐块门槛命中的块数(含被整体门槛抛弃的命中数,用于观测)
     * @param maxScore  本轮检索最高相似度(整体门槛判断依据;无命中为 0)
     */
    public record RagResult(RagStatus status, String context, int hitCount, double maxScore) {
        public static final RagResult EMPTY = new RagResult(RagStatus.NO_KNOWLEDGE, "", 0, 0);
        public boolean ok() { return status == RagStatus.OK; }
    }

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

    /**
     * 跨车型检索(S6 多车型):对多个车型分别检索并合并,标注来源车型。
     * @param modelIds 车型 id 列表(可多个)
     * @param query    查询文本
     * @param topK     每车型返回条数
     * @param minScore 相似度阈值
     * @return 合并后的知识上下文;无命中返回空串
     */
    public String buildContextForModels(List<Long> modelIds, String query, int topK, double minScore) {
        if (modelIds == null || modelIds.isEmpty() || query == null || query.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long modelId : modelIds) {
            if (modelId == null) continue;
            List<Hit> hits = retrieve(modelId, query, topK);
            for (Hit h : hits) {
                if (h.score() < minScore) continue;
                sb.append(h.chunkText()).append("\n---\n");
            }
        }
        return sb.toString();
    }

    /**
     * 生成前必查入口(S6.1「必查+降级可见」):项目关联车型时对每个车型检索并合并。
     * 不抛异常——检索失败降级为 FAILED 状态返回,由调用方决定降级提示;失败细节已记 warn 日志。
     *
     * 状态判定:
     *   无车型对象/逐块过滤后无命中      → NO_KNOWLEDGE(不算失败,不阻断)
     *   有命中但最高相似度 < 整体门槛    → LOW_CONFIDENCE(全部抛弃,不注入 prompt)
     *   检索过程异常(embedding 挂了等)  → FAILED(生成继续,调用方需提示 AI 标注数据缺失)
     *   其余(命中且过整体门槛)          → OK(注入上下文)
     *
     * @param modelIds 车型 id 列表(空/全 null → NO_KNOWLEDGE)
     * @param query    查询文本
     * @param topK     每车型返回条数
     */
    public RagResult retrieveForGeneration(List<Long> modelIds, String query, int topK) {
        if (modelIds == null || modelIds.isEmpty() || query == null || query.isBlank()) {
            return RagResult.EMPTY;
        }
        double minScore = aiProps.getRagMinScore();
        double rejectScore = aiProps.getRagRejectScore();
        StringBuilder sb = new StringBuilder();
        int hitCount = 0;
        double maxScore = 0;
        boolean anyFailure = false;
        for (Long modelId : modelIds) {
            if (modelId == null) continue;
            try {
                List<Hit> hits = retrieve(modelId, query, topK);
                for (Hit h : hits) {
                    if (h.score() < minScore) continue;
                    hitCount++;
                    maxScore = Math.max(maxScore, h.score());
                    sb.append(h.chunkText()).append("\n---\n");
                }
            } catch (Exception e) {
                // 必查但降级可见:单车型失败不抛出,标记 FAILED,继续尝试其余车型
                anyFailure = true;
                log.warn("生成前知识库检索失败 modelId={} query={}: {}", modelId, query, e.getMessage());
            }
        }
        if (anyFailure) {
            return new RagResult(RagStatus.FAILED, "", hitCount, maxScore);
        }
        if (hitCount == 0) {
            return RagResult.EMPTY;
        }
        if (maxScore < rejectScore) {
            // 整体置信度过低:命中了但不相关,全部抛弃,不得与「无命中」混淆
            log.info("知识库检索整体置信度过低已抛弃 modelIds={} hitCount={} maxScore={} rejectScore={} query={}",
                    modelIds, hitCount, maxScore, rejectScore, query);
            return new RagResult(RagStatus.LOW_CONFIDENCE, "", hitCount, maxScore);
        }
        return new RagResult(RagStatus.OK, sb.toString(), hitCount, maxScore);
    }
}
