package com.sparkora.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.car.client.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI PgVectorStore 单表（{@code vector_store}）访问层（10-03 E1）。
 *
 * <p>职责边界：本类只负责「存储 + 相似度检索」——把原来 4 域手写注解 SQL 的存储/检索换为
 * 框架 {@link VectorStore} 单表 + metadata 过滤。业务规则（锚点加权/分层配额/门槛/四态/
 * 子查询/覆盖度）仍留在 {@code CarRagService}，语义不变。
 *
 * <p>metadata 契约（design §3.1）:
 * <pre>
 * {
 *   "domain": "CAR"|"KB"|"NEWS"|"IMAGE",
 *   "refId": 123,            // 域内 id：CAR=car_doc.id / KB=kb_chunk.id / NEWS=news_doc.id / IMAGE=image_asset.id
 *   "modelId": 45,           // 仅 CAR（锚点加权用）；其余省略
 *   "chunkType": "PARAM_GROUP"|"KB_CHUNK"|"NEWS_BODY"|"IMAGE",
 *   "name": "海狮08EV"|"知识标题"|"新闻标题",
 *   "active": true,          // 活表态：软删/停用同步为 false
 *   "embeddingModel": "Qwen3-Embedding-8B"
 * }
 * </pre>
 * {@code content} = chunk_text。
 *
 * <p><b>写入复用已算好的向量</b>：{@link #upsert} 直接经 JdbcTemplate 写入向量字面量，
 * 不调用 {@link VectorStore#add}（那会再次网络嵌入）。这对阶段 A 对拍至关重要——回填复用旧表
 * 向量，嵌入不重算、结果不抖动。读取用 {@link VectorStore#similaritySearch}（内部嵌入 query，
 * 与 {@link EmbeddingClient#embed} 同一模型、等价）。
 *
 * <p><b>id 确定性</b>：{@code id = UUID.nameUUIDFromBytes(domain + ":" + refId)}，使同一
 * 域内引用的行在 upsert/delete 间稳定定位（store 无「按 metadata 更新」API，只能按 id 操作，
 * 或经 JdbcTemplate 直更 metadata）。
 */
@Slf4j
@Service
public class VectorStoreService implements SearchStore {

    private final VectorStore vectorStore;
    private final ObjectMapper json;

    public VectorStoreService(VectorStore vectorStore, ObjectMapper json) {
        this.vectorStore = vectorStore;
        this.json = json;
    }

    /** 域内引用 id → 确定性 uuid（同一 domain+refId 恒定，跨进程一致）。 */
    public static UUID docId(String domain, Long refId) {
        return UUID.nameUUIDFromBytes((domain + ":" + refId).getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 检索 ====================

    /**
     * 按域检索（候选窗口隔离由调用方决定：CAR+KB 合并一次、NEWS 独立一次）。
     * 过滤条件固定为 {@code domain ∈ domains && active == true && embeddingModel == 当前模型}。
     *
     * @param similarityThreshold 0 = ACCEPT_ALL（门槛在 Java 侧用 ragMinScore/ragRejectScore 判，
     *                            保证与旧路径逐条对拍一致）
     */
    public List<Document> searchDomains(Collection<String> domains, String query, int topK,
                                        double similarityThreshold, String embeddingModel) {
        if (domains == null || domains.isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.and(b.in("domain", new ArrayList<Object>(domains)), b.eq("active", true)),
                b.eq("embeddingModel", embeddingModel));
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filter.build())
                .build();
        return vectorStore.similaritySearch(req);
    }

    /**
     * 单车型 CAR 域检索（对齐旧 {@code CarDocEmbeddingMapper.searchTopK}：{@code model_id} 过滤）。
     * 过滤：{@code domain == CAR && modelId == modelId && active && embeddingModel}。
     */
    @Override
    public List<Document> searchByModel(Long modelId, String query, int topK,
                                        double similarityThreshold, String embeddingModel) {
        if (modelId == null || query == null || query.isBlank() || topK <= 0) return List.of();
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.and(b.eq("domain", VectorDomain.CAR.name()), b.eq("modelId", modelId)),
                b.and(b.eq("active", true), b.eq("embeddingModel", embeddingModel)));
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filter.build())
                .build();
        return vectorStore.similaritySearch(req);
    }

    /**
     * 图片域检索（独立入口）。{@code refIds} 非空时限定候选集（标签 AND 预过滤白名单，候选集 ≤500）。
     * 过滤：{@code domain == IMAGE && active == true && embeddingModel == 当前模型 && refId ∈ ids}。
     */
    @Override
    public List<Document> searchImages(String query, Collection<Long> refIds, double minScore,
                                       int topK, String embeddingModel) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.and(b.eq("domain", VectorDomain.IMAGE.name()), b.eq("active", true)),
                b.eq("embeddingModel", embeddingModel));
        if (refIds != null && !refIds.isEmpty()) {
            List<Object> ids = new ArrayList<>(refIds.size());
            for (Long id : refIds) if (id != null) ids.add(id);
            if (!ids.isEmpty()) filter = b.and(filter, b.in("refId", ids));
        }
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(minScore)
                .filterExpression(filter.build())
                .build();
        return vectorStore.similaritySearch(req);
    }

    // ==================== 写入 / 生命周期 ====================

    /**
     * 插入或覆盖一条 store 行（同 id 幂等）。**直接写向量字面量，不重新嵌入**。
     *
     * @param vector pgvector 字面量字符串（如 "[0.1,0.2,...]"）
     */
    public void upsert(String domain, Long refId, Long modelId, String chunkType, String name,
                       boolean active, String embeddingModel, String content, String vector) {
        upsert(domain, refId, modelId, chunkType, name, active, embeddingModel, content, vector, null);
    }

    /**
     * 插入或覆盖一条 store 行（同 id 幂等），并写入**可选扩展 metadata**。
     *
     * <p>10-03 E3：KB 域新增 {@code source}/{@code effectiveFrom}/{@code effectiveTo}/{@code tags}
     * 维度（ISO 日期字符串 / 标签列表），供未来检索过滤。{@code extraMeta} 为 null 或空时行为与
     * 旧 9 参重载完全一致（CAR/NEWS/IMAGE 域零影响）；值为 null 的键**不写入**（不留空洞键）。
     * 同 id 冲突时整条 metadata 覆盖，故调用方每次都须传全量扩展键（否则旧键被抹掉属预期）。
     *
     * @param vector    pgvector 字面量字符串（如 "[0.1,0.2,...]"）
     * @param extraMeta 扩展 metadata（可空）；值为 null 的条目跳过
     */
    public void upsert(String domain, Long refId, Long modelId, String chunkType, String name,
                       boolean active, String embeddingModel, String content, String vector,
                       Map<String, Object> extraMeta) {
        if (domain == null || refId == null || content == null || vector == null) return;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("domain", domain);
        meta.put("refId", refId);
        if (modelId != null) meta.put("modelId", modelId);
        meta.put("chunkType", chunkType);
        meta.put("name", name == null ? "" : name);
        meta.put("active", active);
        meta.put("embeddingModel", embeddingModel);
        if (extraMeta != null) {
            for (Map.Entry<String, Object> e : extraMeta.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) meta.put(e.getKey(), e.getValue());
            }
        }
        String metadataJson;
        try {
            metadataJson = json.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("store metadata 序列化失败(跳过) domain={} refId={}: {}", domain, refId, e.getMessage());
            return;
        }
        JdbcTemplate jt = nativeClient();
        if (jt == null) return;
        jt.update("INSERT INTO vector_store (id, content, metadata, embedding) "
                        + "VALUES (?::uuid, ?, ?::jsonb, ?::vector) "
                        + "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, "
                        + "metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding",
                docId(domain, refId).toString(), content, metadataJson, vector);
    }

    /** 物理删除指定域内引用集合的 store 行（父删除/重建前置清理）。 */
    public int deleteByRef(String domain, Collection<Long> refIds) {
        if (domain == null || refIds == null || refIds.isEmpty()) return 0;
        JdbcTemplate jt = nativeClient();
        if (jt == null) return 0;
        int n = 0;
        for (Long refId : refIds) {
            if (refId == null) continue;
            n += jt.update("DELETE FROM vector_store WHERE id = ?::uuid", docId(domain, refId).toString());
        }
        return n;
    }

    /** 按 models.metadata.modelId 物理删除 CAR 域全部行（车型删除兜底，含历史逻辑删除块的残留行）。 */
    public int deleteByCarModel(Long modelId) {
        if (modelId == null) return 0;
        JdbcTemplate jt = nativeClient();
        if (jt == null) return 0;
        return jt.update("DELETE FROM vector_store WHERE (metadata->>'domain') = ? "
                + "AND (metadata->>'modelId')::bigint = ?", VectorDomain.CAR.name(), modelId);
    }

    /**
     * 同步活表态（R3 关键风险点）：软删/停用 → false；重建/启用 → true。
     * Spring AI 无「按 metadata 更新」API，故用 native JdbcTemplate 直更 jsonb。
     */
    public int setActive(String domain, Collection<Long> refIds, boolean active) {
        if (domain == null || refIds == null || refIds.isEmpty()) return 0;
        JdbcTemplate jt = nativeClient();
        if (jt == null) return 0;
        int n = 0;
        for (Long refId : refIds) {
            if (refId == null) continue;
            n += jt.update("UPDATE vector_store SET metadata = "
                            + "(jsonb_set((metadata)::jsonb, '{active}', to_jsonb(?::boolean), true))::json "
                            + "WHERE id = ?::uuid",
                    active, docId(domain, refId).toString());
        }
        return n;
    }

    /** 当前 store 中已存在行 id 集（回填差集用）。异常仅 warn 返回空集。 */
    public java.util.Set<String> existingIds() {
        JdbcTemplate jt = nativeClient();
        if (jt == null) return java.util.Set.of();
        try {
            List<String> ids = jt.queryForList("SELECT id::text FROM vector_store", String.class);
            return new java.util.HashSet<>(ids);
        } catch (Exception e) {
            log.warn("读取 store 已有 id 集失败(按空集处理): {}", e.getMessage());
            return java.util.Set.of();
        }
    }

    /** 取 native JdbcTemplate；不可用时返回 null（不抛，调用方降级跳过）。 */
    private JdbcTemplate nativeClient() {
        return vectorStore.getNativeClient().map(c -> (JdbcTemplate) c).orElse(null);
    }
}
