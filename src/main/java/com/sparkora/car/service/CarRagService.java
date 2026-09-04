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
    private final com.sparkora.mapper.KbChunkEmbeddingMapper kbEmbMapper;
    private final EmbeddingClient embeddingClient;
    private final AiProperties aiProps;

    public CarRagService(CarDocEmbeddingMapper embMapper,
                         com.sparkora.mapper.KbChunkEmbeddingMapper kbEmbMapper,
                         EmbeddingClient embeddingClient, AiProperties aiProps) {
        this.embMapper = embMapper;
        this.kbEmbMapper = kbEmbMapper;
        this.embeddingClient = embeddingClient;
        this.aiProps = aiProps;
    }

    /** 检索结果项。 */
    public record Hit(String chunkText, double score) {}

    /** 带类型与分数的检索命中(配额分层用)。chunkType: MODEL_INFO/PARAM_GROUP/RIGHTS/FEATURE。 */
    public record TypedHit(String chunkText, String chunkType, double score) {}

    /** 知识库检索状态:OK 命中并过门槛 / LOW_CONFIDENCE 整体置信度过低已抛弃 / FAILED 检索异常降级 / NO_KNOWLEDGE 无车型对象或无命中。 */
    public enum RagStatus { OK, LOW_CONFIDENCE, FAILED, NO_KNOWLEDGE }

    /**
     * 检索结果(供生成链路「必查+降级可见」使用)。
     * @param status      检索状态
     * @param context     注入 prompt 的知识上下文文本(抛弃/失败/无命中时为空串)
     * @param hitCount    通过逐块门槛命中的块数(含被整体门槛抛弃的命中数,用于观测)
     * @param maxScore    本轮检索最高相似度(整体门槛判断依据;无命中为 0)
     * @param coveredText 已覆盖参数摘要(「参数名→值」拼接,逗号分隔;未覆盖场景为空串)
     */
    public record RagResult(RagStatus status, String context, int hitCount, double maxScore, String coveredText) {
        public static final RagResult EMPTY = new RagResult(RagStatus.NO_KNOWLEDGE, "", 0, 0, "");
        public RagResult(RagStatus status, String context, int hitCount, double maxScore) {
            this(status, context, hitCount, maxScore, "");
        }
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
     * 带类型的检索(S6.2 配额分层用):同 retrieve,额外返回 chunk_type。
     */
    public List<TypedHit> retrieveTyped(Long modelId, String query, int topK) {
        if (modelId == null || query == null || query.isBlank()) return List.of();
        String vec = embeddingClient.embed(query);
        List<Map<String, Object>> rows = embMapper.searchTopK(modelId, vec, topK);
        List<TypedHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String text = row.get("chunkText") == null ? "" : String.valueOf(row.get("chunkText"));
            String type = row.get("chunkType") == null ? "PARAM_GROUP" : String.valueOf(row.get("chunkType"));
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            hits.add(new TypedHit(text, type, score));
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
     * 生成前必查入口(S6.1「必查+降级可见」;S6.2 升级为分层配额+子查询检索):
     * 项目关联车型时对每个车型检索并合并。不抛异常——检索失败降级为 FAILED 状态返回,
     * 由调用方决定降级提示;失败细节已记 warn 日志。
     *
     * S6.2 检索策略:
     *   1) 主查询(整句 topic)+ 参数级子查询(query 含「价格/续航/油耗/电池/尺寸」等参数词时派生「车型名+参数词」子查询),
     *      子查询命中的参数块单独配额,避免权益/概述块挤占 topK;
     *   2) 块类型配额:PARAM_GROUP(含数值的参数块)优先保留;权益类(RIGHTS/FEATURE)合计上限 1/3 配额;
     *   3) 零信息兜底:块文本仅含 1 个换行(即只有标题行)的块视为表头块丢弃。
     *
     * 状态判定(与 S6.1 一致):
     *   无车型对象/逐块过滤后无命中      → NO_KNOWLEDGE(不算失败,不阻断)
     *   有命中但最高相似度 < 整体门槛    → LOW_CONFIDENCE(全部抛弃,不注入 prompt)
     *   检索过程异常(embedding 挂了等)  → FAILED(生成继续,调用方需提示 AI 标注数据缺失)
     *   其余(命中且过整体门槛)          → OK(注入上下文)
     *
     * S7 双源升级:
     *   - 车型域(modelIds 非空):沿用 S6.2 分层配额+子查询,行为不变;
     *   - 通用域(KB,AI_RAG_KB_ENABLED 时):始终检索,独立配额 ragKbTopk(与车型域互不挤占),
     *     块注入时加「【通用知识】」前缀;未关联车型(modelIds 空)不再短路,仍查通用域;
     *   - 覆盖度声明(coveredText)仅统计车型参数块(KB 块不参与「参数覆盖」语义);
     *   - 多源失败:任一域异常 → FAILED(语义不变),但另一域正常结果仍注入(降级可见,非硬阻断)。
     *
     * @param modelIds 车型 id 列表(可空;空=未关联车型,仅查通用域)
     * @param query    查询文本(主查询)
     * @param topK     每车型每查询返回条数(车型域)
     */
    public RagResult retrieveForGeneration(List<Long> modelIds, String query, int topK) {
        if (query == null || query.isBlank()) {
            return RagResult.EMPTY;
        }
        boolean hasModels = modelIds != null && modelIds.stream().anyMatch(java.util.Objects::nonNull);
        if (!hasModels && !aiProps.isRagKbEnabled()) {
            // 未关联车型且通用域关闭 → S6.2 原行为(无知识对象)
            return RagResult.EMPTY;
        }
        double minScore = aiProps.getRagMinScore();
        double rejectScore = aiProps.getRagRejectScore();
        List<TypedHit> merged = new ArrayList<>();
        boolean anyFailure = false;
        List<TypedHit> kbHits = List.of();
        for (Long modelId : modelIds == null ? List.<Long>of() : modelIds) {
            if (modelId == null) continue;
            try {
                // 主查询
                List<TypedHit> primary = retrieveTyped(modelId, query, topK);
                merged.addAll(primary);
                // 参数级子查询(S6.2 P0-3):从查询文本抽参数词派生子查询;仅补主查询未命中的块(按 chunkText 去重)
                java.util.Set<String> seen = new java.util.HashSet<>();
                primary.forEach(h -> seen.add(h.chunkText()));
                for (String sub : deriveSubQueries(query)) {
                    for (TypedHit h : retrieveTyped(modelId, sub, topK)) {
                        if (seen.add(h.chunkText())) merged.add(h);
                    }
                }
            } catch (Exception e) {
                // 必查但降级可见:单车型失败不抛出,标记 FAILED,继续尝试其余车型
                anyFailure = true;
                log.warn("生成前知识库检索失败 modelId={} query={}: {}", modelId, query, e.getMessage());
            }
        }
        // 通用域检索(S7):独立配额,不与车型域混算
        if (aiProps.isRagKbEnabled()) {
            try {
                kbHits = retrieveKb(query, aiProps.getRagKbTopk());
            } catch (Exception e) {
                anyFailure = true;
                log.warn("生成前通用知识库检索失败 query={}: {}", query, e.getMessage());
            }
        }
        int rawHit = 0;
        double maxScore = 0;
        for (TypedHit h : merged) {
            if (h.score() < minScore) continue;
            rawHit++;
            maxScore = Math.max(maxScore, h.score());
        }
        // KB 块并入统计(逐块门槛同 minScore;maxScore 跨两域取最大)
        java.util.List<TypedHit> kbPassed = new java.util.ArrayList<>();
        for (TypedHit h : kbHits) {
            if (h.score() < minScore) continue;
            rawHit++;
            maxScore = Math.max(maxScore, h.score());
            kbPassed.add(h);
        }
        if (anyFailure) {
            return new RagResult(RagStatus.FAILED, "", rawHit, maxScore);
        }
        if (rawHit == 0) {
            return RagResult.EMPTY;
        }
        if (maxScore < rejectScore) {
            // 整体置信度过低:命中了但不相关,全部抛弃,不得与「无命中」混淆
            log.info("知识库检索整体置信度过低已抛弃 modelIds={} hitCount={} maxScore={} rejectScore={} query={}",
                    modelIds, rawHit, maxScore, rejectScore, query);
            return new RagResult(RagStatus.LOW_CONFIDENCE, "", rawHit, maxScore);
        }

        // 知识来源行(S7):前端展示与 prompt 均可见双源构成
        String sourceLine = kbPassed.isEmpty() ? "知识来源：车型数据" 
                : (merged.isEmpty() ? "知识来源：通用知识库" : "知识来源：车型数据 + 通用知识库");
        // 分层配额去重合并(车型域):参数块优先,权益/特性类合计不超过 1/3;表头块(仅 1 行)丢弃
        List<TypedHit> selected = applyQuota(merged, topK * Math.max(1,
                modelIds == null ? 0 : (int) modelIds.stream().filter(java.util.Objects::nonNull).count()));
        StringBuilder sb = new StringBuilder();
        StringBuilder covered = new StringBuilder();
        if (!merged.isEmpty() || !kbPassed.isEmpty()) sb.append(sourceLine).append("\n---\n");
        // KB 块独立配额注入(带来源前缀,不参与车型参数覆盖度统计)
        for (TypedHit h : kbPassed) {
            sb.append("【通用知识】").append(h.chunkText()).append("\n---\n");
        }
        for (TypedHit h : selected) {
            sb.append(h.chunkText()).append("\n---\n");
            covered.append(extractParamSummary(h.chunkText()));
        }
        log.info("RAG 分层配额检索完成 modelIds={} raw={} selected={} kb={} maxScore={}",
                modelIds, rawHit, selected.size(), kbPassed.size(), maxScore);
        return new RagResult(RagStatus.OK, sb.toString(), rawHit, maxScore, covered.toString());
    }

    /**
     * 通用域检索(S7):全库 top-K,无车型约束。供 retrieveForGeneration 与 KB 问答使用。
     * 返回 TypedHit(chunkType 固定 "KB_CHUNK")。
     */
    public List<TypedHit> retrieveKb(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        String vec = embeddingClient.embed(query);
        List<Map<String, Object>> rows = kbEmbMapper.searchTopK(vec, topK);
        List<TypedHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String text = row.get("chunkText") == null ? "" : String.valueOf(row.get("chunkText"));
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            hits.add(new TypedHit(text, "KB_CHUNK", score));
        }
        return hits;
    }

    /** 查询文本中包含的参数关键词 → 子查询(「参数词 + 车型上下文」由调用方模型名已含于 query 时自动生效)。 */
    private static final String[] PARAM_TERMS = {
            "价格", "续航", "油耗", "电池", "尺寸", "动力", "充电", "配置", "安全", "智能", "空间", "质保", "电机", "发动机", "底盘"
    };

    /**
     * 从查询文本派生参数级子查询:S6.2 P0-3。
     * 仅当查询里同时含车型名线索与参数词时,按参数词单独拆出子查询(如「海狮08EV 续航」)。
     * 实现从简:query 含参数词 → 为每个参数词生成原查询文本(整句已含车型名,embedding 相似度按词内语义对齐);
     * 同时追加「车型名+参数词」组合(取 query 中出现的车型名——从命中块反推不可行,直接用参数词拼接原查询前 12 字符)。
     */
    List<String> deriveSubQueries(String query) {
        List<String> subs = new ArrayList<>();
        String compact = query.replaceAll("[?？!！,，。;；、]", "").trim();
        for (String term : PARAM_TERMS) {
            if (query.contains(term)) {
                subs.add(compact.length() > 12 ? compact.substring(0, 12) + " " + term : term);
            }
        }
        return subs;
    }

    /**
     * 分层配额选择(S6.2 P0-2):PARAM_GROUP/MODEL_INFO 优先,RIGHTS/FEATURE 合计上限 = 总配额 1/3;
     * 零信息表头块(去空白后仅 1 行)丢弃。按分数降序在类型内取。
     */
    static List<TypedHit> applyQuota(List<TypedHit> hits, int totalQuota) {
        if (hits == null || hits.isEmpty()) return List.of();
        List<TypedHit> core = new ArrayList<>();   // PARAM_GROUP / MODEL_INFO
        List<TypedHit> soft = new ArrayList<>();   // RIGHTS / FEATURE
        for (TypedHit h : hits) {
            if (isHeaderChunk(h)) continue;
            boolean softType = "RIGHTS".equals(h.chunkType()) || "FEATURE".equals(h.chunkType());
            (softType ? soft : core).add(h);
        }
        core.sort((a, b) -> Double.compare(b.score(), a.score()));
        soft.sort((a, b) -> Double.compare(b.score(), a.score()));
        int softCap = Math.max(1, totalQuota / 3);
        List<TypedHit> out = new ArrayList<>(core.subList(0, Math.min(core.size(), totalQuota)));
        if (out.size() < totalQuota) {
            out.addAll(soft.subList(0, Math.min(soft.size(), Math.min(softCap, totalQuota - out.size()))));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * 零信息表头块判定(仅对参数块):PARAM_GROUP 块去除「参数分组:」标题行后不足 1 条参数行
     * (即「海狮08EV参数表及配置表」这类只有标题的块)。MODEL_INFO/RIGHTS/FEATURE 单行属正常,不丢。
     */
    static boolean isHeaderChunk(TypedHit h) {
        if (h == null || h.chunkText() == null) return true;
        if (!"PARAM_GROUP".equals(h.chunkType())) return false;
        String[] lines = h.chunkText().strip().split("\\R");
        long meaningful = java.util.Arrays.stream(lines)
                .filter(l -> !l.startsWith("参数分组："))
                .count();
        return meaningful < 1;
    }

    /** 从块文本抽取「参数名→值」摘要(仅 PARAM_GROUP 类「key：value」行),截断防超长。 */
    static String extractParamSummary(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : chunkText.split("\n")) {
            int idx = line.indexOf('：');
            if (idx <= 0 || idx == line.length() - 1) continue;
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            if (key.isEmpty() || "有".equals(val) || "无".equals(val) || "可选装".equals(val)) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(key).append("→").append(val);
            if (sb.length() > 400) { sb.append("…"); break; }
        }
        return sb.toString();
    }
}
