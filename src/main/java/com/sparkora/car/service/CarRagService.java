package com.sparkora.car.service;

import com.sparkora.ai.NumericSignature;
import com.sparkora.ai.vector.SearchStore;
import com.sparkora.ai.vector.VectorDomain;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索服务。供文章生成(BriefService/VersionService)与内部问答共用。
 *
 * 流程:query → embedding → pgvector 余弦相似度 top-K → 组装知识上下文。
 * 检索结果作为事实约束注入 prompt,衔接 fact_risks 防编造。
 *
 * 10-03 E1:存储+相似度检索改走 Spring AI PgVectorStore 单表(经 {@link SearchStore}),
 * 原 4 段手写 UNION SQL 由「CAR+KB 合并检索一次 + NEWS 独立检索一次 + Java 合并」复现候选
 * 窗口隔离语义;业务规则(锚点/配额/门槛/四态/子查询/覆盖度)不变。
 */
@Slf4j
@Service
public class CarRagService {

    private final SearchStore store;
    private final EmbeddingClient embeddingClient;
    private final AiProperties aiProps;

    /** A rerank(10-03-a-rerank):可空——关闭态/单测直 new(3 参兼容构造器)时为 null,不调用。 */
    private final Reranker reranker;

    /** Spring 注入构造器:注入 {@link Reranker}(LlmReranker)。 */
    @Autowired
    public CarRagService(SearchStore store, EmbeddingClient embeddingClient, AiProperties aiProps, Reranker reranker) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.aiProps = aiProps;
        this.reranker = reranker;
    }

    /**
     * 兼容构造器(无 reranker):既有单测直 new 与关闭态回退用。reranker=null 时检索行为与现状逐条一致
     * → 零回归。
     */
    public CarRagService(SearchStore store, EmbeddingClient embeddingClient, AiProperties aiProps) {
        this(store, embeddingClient, aiProps, null);
    }

    /** 检索结果项。 */
    public record Hit(String chunkText, double score) {}

    /** 带类型与分数的检索命中(配额分层用)。chunkType: MODEL_INFO/PARAM_GROUP/RIGHTS/FEATURE/KB_CHUNK。 */
    public record TypedHit(String chunkText, String chunkType, double score) {}

    /**
     * 统一检索命中(S8):TypedHit + 来源域与来源名(车型名/知识标题),供行内来源标注与锚点加权。
     *
     * @param docId 域内文档块 id（09-15 qa-auto-illustrate 补读；语义随 source 变化：
     *              CAR=sparkora_car_chunk.id / KB=sparkora_kb_chunk.id / NEWS=sparkora_news_doc.id；可空）。
     *              供消费方定位来源实体（如 NEWS 反查来源新闻封面图），检索 SQL 本已 SELECT，此前读行时丢弃。
     * @param sourceType 10-05 E：NEWS 域内来源类型（byd-news / user-source / ...；可空，null 视为 byd-news）。
     * @param category   10-05 E：NEWS 域内来源分类（官方新闻/销量数据/投诉榜/政策公示/行业资讯；可空）。
     */
    public record UnifiedHit(String chunkText, String chunkType, double score,
                             String source, Long modelId, String modelName, Long docId,
                             String sourceType, String category) {
        /** 兼容构造器（无 sourceType/category）：既有调用方（锚点加权重建/单测）字段为空。 */
        public UnifiedHit(String chunkText, String chunkType, double score,
                          String source, Long modelId, String modelName, Long docId) {
            this(chunkText, chunkType, score, source, modelId, modelName, docId, null, null);
        }
        TypedHit toTyped() { return new TypedHit(chunkText, chunkType, score); }
    }

    /** 知识库检索状态:OK 命中并过门槛 / LOW_CONFIDENCE 整体置信度过低已抛弃 / FAILED 检索异常降级 / NO_KNOWLEDGE 无车型对象或无命中。 */
    public enum RagStatus { OK, LOW_CONFIDENCE, FAILED, NO_KNOWLEDGE }

    /**
     * 知识引用条目(S6.1 扩展:rag_citations 落库,前端简报/版本页可核查「AI 引用了哪些知识」)。
     * @param source     来源域 CAR(车型数据)| KB(通用知识库)| NEWS(官方新闻)
     * @param modelName  车型名或知识标题(检索块自带的标注名)
     * @param chunkType  块类型 MODEL_INFO/PARAM_GROUP/RIGHTS/FEATURE/KB_CHUNK/NEWS_BODY
     * @param score      相似度分数(锚点加权后的排序分)
     * @param chunkText  块文本摘要(截断,详见 CITE_TEXT_MAX)
     * @param docId      域内文档块 id（09-15 qa-auto-illustrate；随 source 变化，可空；供问答配图定位来源）。
     *                   保留 5 参构造器以兼容既有调用方（简报/深度检索/测试），既有代码不受影响。
     * @param sourceType 10-05 E（P0 字段贯通）：NEWS 域内来源类型（byd-news/user-source；可空）。
     *                   **本字段是送到 KnowledgeSearchTool 的实际载体**——只加在 UnifiedHit 会在映射时丢弃。
     * @param category   10-05 E：NEWS 域内来源分类（官方新闻/销量数据/...；可空）。
     */
    public record Citation(String source, String modelName, String chunkType, double score,
                           String chunkText, Long docId, String sourceType, String category) {
        /** 兼容构造器（docId=null）：既有 5 参调用方（BriefService/KnowledgeSearchTool/测试）编译与行为不变。 */
        public Citation(String source, String modelName, String chunkType, double score, String chunkText) {
            this(source, modelName, chunkType, score, chunkText, null, null, null);
        }
        /** 兼容构造器（docId 给定、sourceType/category 为空）：09-15 起既有 6 参调用方不受影响。 */
        public Citation(String source, String modelName, String chunkType, double score,
                        String chunkText, Long docId) {
            this(source, modelName, chunkType, score, chunkText, docId, null, null);
        }
    }

    /** 引用摘要单条文本截断长度(前端展示只需首行概要,控制 rag_citations 体积)。 */
    private static final int CITE_TEXT_MAX = 120;

    /** 单次检索引用条目上限(与注入 prompt 的 selected 列表同量级,防 JSON 超列)。 */
    private static final int CITE_MAX = 24;

    /**
     * 检索结果(供生成链路「必查+降级可见」使用)。
     * @param status      检索状态
     * @param context     注入 prompt 的知识上下文文本(抛弃/失败/无命中时为空串)
     * @param hitCount    通过逐块门槛命中的块数(含被整体门槛抛弃的命中数,用于观测)
     * @param maxScore    本轮检索最高相似度(整体门槛判断依据;无命中为 0)
     * @param coveredText 已覆盖事实摘要（10-03 E5：CAR 参数块「参数名→值」+ KB/NEWS 数值事实；
     *                    未覆盖场景为空串）
     * @param citations   注入 prompt 的命中块明细(与 context 同源;OK 时非空,其余状态为空列表)
     */
    public record RagResult(RagStatus status, String context, int hitCount, double maxScore,
                            String coveredText, java.util.List<Citation> citations) {
        public static final RagResult EMPTY =
                new RagResult(RagStatus.NO_KNOWLEDGE, "", 0, 0, "", java.util.List.of());
        public RagResult(RagStatus status, String context, int hitCount, double maxScore) {
            this(status, context, hitCount, maxScore, "");
        }
        /** 兼容旧调用(不带 citations,视作空)。 */
        public RagResult(RagStatus status, String context, int hitCount, double maxScore, String coveredText) {
            this(status, context, hitCount, maxScore, coveredText, java.util.List.of());
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
        List<Document> docs = store.searchByModel(modelId, query, topK, 0, embeddingClient.modelName());
        List<Hit> hits = new ArrayList<>();
        for (Document doc : docs) {
            hits.add(new Hit(text(doc), doc.getScore() == null ? 0 : doc.getScore()));
        }
        return hits;
    }

    /**
     * 带类型的检索(S6.2 配额分层用):同 retrieve,额外返回 chunk_type。
     */
    public List<TypedHit> retrieveTyped(Long modelId, String query, int topK) {
        if (modelId == null || query == null || query.isBlank()) return List.of();
        List<Document> docs = store.searchByModel(modelId, query, topK, 0, embeddingClient.modelName());
        List<TypedHit> hits = new ArrayList<>();
        for (Document doc : docs) {
            String type = metaString(doc, "chunkType");
            hits.add(new TypedHit(text(doc), type == null ? "PARAM_GROUP" : type,
                    doc.getScore() == null ? 0 : doc.getScore()));
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
    /**
     * 生成前必查入口(S8 统一检索):
     * 车型域与 KB 域**同向量空间全库检索**(单表 store,10-03 E1/E6),「项目关联车型」降为锚点加权——
     * 数据可达性不再依赖用户手动关联(文章18误伤:海狮08数据在库,未关联即查不到)。
     *
     * 检索策略(S6.2 资产全部保留):
     *   1) 主查询 + 参数级子查询,均走统一检索,chunkText 去重合并;
     *   2) 锚点加权:CAR 块 modelId∈anchorModelIds → score × AI_RAG_ANCHOR_BOOST(重排,非过滤);
     *   3) 配额:PARAM_GROUP/MODEL_INFO 优先,RIGHTS/FEATURE ≤1/3,KB_CHUNK 独立配额 ragKbTopk;
     *      AI_RAG_KB_ENABLED=false 时 KB 块在配额层排除(等价 S6 行为);
     *      C2 起 NEWS 块独立配额 ragNewsTopk(不受 ragKbEnabled 控制,0=关闭 NEWS 注入);
     *   4) 行内来源标注:【车型数据:名称】/【通用知识:标题】/【官方新闻:标题】,首行「知识来源:…」按命中构成。
     *
     * 状态判定(S6.1 语义不变):
     *   无命中 → NO_KNOWLEDGE;命中但最高分 < rejectScore → LOW_CONFIDENCE(全抛弃);
     *   检索异常 → FAILED(降级可见);其余 → OK。
     *
     * @param query           查询文本(主查询)
     * @param topK            注入块数上限(核心块)
     * @param anchorModelIds  锚点车型 id(项目关联;可空,仅影响加权)
     */
    public RagResult retrieveForGeneration(String query, int topK, List<Long> anchorModelIds) {
        if (query == null || query.isBlank()) {
            return RagResult.EMPTY;
        }
        double minScore = aiProps.getRagMinScore();
        double rejectScore = aiProps.getRagRejectScore();
        boolean kbEnabled = aiProps.isRagKbEnabled();
        java.util.Set<Long> anchors = anchorModelIds == null ? java.util.Set.of()
                : anchorModelIds.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<UnifiedHit> merged = new ArrayList<>();
        boolean anyFailure = false;
        // C2:新闻域块数远大于车型/KB(167 篇≈1300+ 块)。候选窗口按域隔离
        // (CAR+KB 合并窗口 / NEWS 独立窗口,经 searchDomains 两次调用复现),故此处过采样沿用
        // C2 前口径 max(topK*4,32) 即可——保持 CAR/KB 候选集与行为不变,NEWS 不挤占。
        int oversample = Math.max(topK * 4, 32);
        try {
            // 主查询(统一全库;按域隔离候选窗口,配额/加权后再截)
            List<UnifiedHit> primary = retrieveUnified(query, oversample);
            merged.addAll(primary);
            // 参数级子查询(S6.2):同走统一检索,chunkText 去重补命中
            java.util.Set<String> seen = new java.util.HashSet<>();
            primary.forEach(h -> seen.add(h.chunkText()));
            for (String sub : deriveSubQueries(query)) {
                for (UnifiedHit h : retrieveUnified(sub, Math.max(topK * 2, 16))) {
                    if (seen.add(h.chunkText())) merged.add(h);
                }
            }
        } catch (Exception e) {
            // 必查但降级可见:检索失败不抛出,标 FAILED
            anyFailure = true;
            log.warn("生成前统一知识库检索失败 query={}: {}", query, e.getMessage());
        }
        // A rerank(10-03-a-rerank,design §3.1):候选合并去重后、锚点加权/配额前插入 LLM 重排。
        // 仅开关开启且注入了 Reranker 时执行;**只改顺序、不改分数**——maxScore/门槛/四态仍基于原分数。
        // 失败/超时/返回异常已由 Reranker 内部降级为原序,此处再兜一层,绝不阻断生成。
        boolean reranked = false;
        if (aiProps.isRagRerankEnabled() && reranker != null && !merged.isEmpty()) {
            try {
                List<UnifiedHit> re = reranker.rerank(query, merged, aiProps.getRagRerankTopN());
                if (re != null && re.size() == merged.size()) {
                    merged = re;
                    reranked = true;
                } else {
                    log.warn("LLM 重排返回集合与输入不一致,回退原序 expected={} actual={}",
                            merged.size(), re == null ? 0 : re.size());
                }
            } catch (Exception e) {
                log.warn("LLM 重排异常,回退原序: {}", e.getClass().getSimpleName());
            }
        }
        // 重排顺序映射(启用时):配额选择与最终排序按重排名次,关闭时保持既有「按分数降序」逐字等价。
        // 说明:锚点加权会重建 UnifiedHit 对象(身份变化),故在 boost 之后按**位置**与 merged 一一对应建映射。
        final boolean rerankOrder = reranked;
        final java.util.IdentityHashMap<UnifiedHit, Integer> rerankRank = new java.util.IdentityHashMap<>();

        // 锚点加权(S8):CAR 块 modelId∈anchor → 分数 × boost(重排用,不改变相似度门槛判定基数)
        double boost = aiProps.getRagAnchorBoost();
        List<UnifiedHit> boosted = new ArrayList<>();
        for (UnifiedHit h : merged) {
            if ("CAR".equals(h.source()) && anchors.contains(h.modelId())) {
                // 注意:重建 UnifiedHit 时必须透传 docId(NEWS 不走此分支,但漏传会让域内 id 在加权后丢失),
                // 10-05 E 起 sourceType/category 同样必须透传(字段贯通 P0,漏传会让下游 F 拿不到来源类型)
                boosted.add(new UnifiedHit(h.chunkText(), h.chunkType(), Math.min(1.0, h.score() * boost),
                        h.source(), h.modelId(), h.modelName(), h.docId(), h.sourceType(), h.category()));
            } else {
                boosted.add(h);
            }
        }
        // boost 与 merged 位置一一对应(仅重建对象/改分,不重排),故按位置建重排名次映射。
        if (rerankOrder) {
            for (int i = 0; i < boosted.size(); i++) rerankRank.put(boosted.get(i), i);
        }
        final java.util.Comparator<UnifiedHit> orderCmp = rerankOrder
                ? java.util.Comparator.comparingInt(h -> rerankRank.getOrDefault(h, Integer.MAX_VALUE))
                : (a, b) -> Double.compare(b.score(), a.score());
        int rawHit = 0;
        double maxScore = 0;
        for (UnifiedHit h : boosted) {
            if (h.score() < minScore) continue;
            rawHit++;
            maxScore = Math.max(maxScore, h.score());
        }
        if (anyFailure) {
            return new RagResult(RagStatus.FAILED, "", rawHit, maxScore);
        }
        if (rawHit == 0) {
            return RagResult.EMPTY;
        }
        if (maxScore < rejectScore) {
            log.info("统一知识库检索整体置信度过低已抛弃 anchors={} hitCount={} maxScore={} rejectScore={} query={}",
                    anchors, rawHit, maxScore, rejectScore, query);
            return new RagResult(RagStatus.LOW_CONFIDENCE, "", rawHit, maxScore);
        }
        // 统一配额选择:核心(PARAM_GROUP/MODEL_INFO)优先 + 权益类 ≤1/3 + KB/新闻独立配额
        List<UnifiedHit> coreCandidates = new ArrayList<>();
        List<UnifiedHit> softCandidates = new ArrayList<>();
        List<UnifiedHit> kbCandidates = new ArrayList<>();
        List<UnifiedHit> newsCandidates = new ArrayList<>();
        List<UnifiedHit> sourceCandidates = new ArrayList<>();
        int newsQuota = Math.max(0, aiProps.getRagNewsTopk());
        int sourceQuota = Math.max(0, aiProps.getRagSourceTopk());
        for (UnifiedHit h : boosted) {
            if (h.score() < minScore) continue;
            if (isHeaderChunk(h.toTyped())) continue;
            if ("KB".equals(h.source())) {
                if (kbEnabled) coreCandidates.add(h);   // KB 块并入核心候选池,配额阶段独立截取
            } else if ("NEWS".equals(h.source())) {
                // 10-05 E:NEWS 域内按 sourceType 二级隔离 —— byd-news(含 sourceType==null 兜底)用原配额,
                // user-source 用独立配额(默认 0=off),防止用户采集源挤占 BYD(AC-E3)
                if (isBydNews(h)) {
                    if (newsQuota > 0) newsCandidates.add(h);
                } else {
                    if (sourceQuota > 0) sourceCandidates.add(h);
                }
            } else if ("RIGHTS".equals(h.chunkType()) || "FEATURE".equals(h.chunkType())) {
                softCandidates.add(h);
            } else {
                coreCandidates.add(h);
            }
        }
        coreCandidates.sort(orderCmp);
        softCandidates.sort(orderCmp);
        newsCandidates.sort(orderCmp);
        sourceCandidates.sort(orderCmp);
        // 核心块配额:carTopK 给车型核心块(锚点车型数×topK,至少 topK),KB/新闻独立配额不挤占
        int carQuota = Math.max(topK, topK * Math.max(1, anchors.size()));
        List<UnifiedHit> carSelected = coreCandidates.stream()
                .filter(h -> !"KB".equals(h.source()) && !"NEWS".equals(h.source())).toList();
        int kbQuota = kbEnabled ? aiProps.getRagKbTopk() : 0;
        List<UnifiedHit> kbSelected = coreCandidates.stream()
                .filter(h -> "KB".equals(h.source())).limit(kbQuota).toList();
        List<UnifiedHit> newsSelected = newsCandidates.stream().limit(newsQuota).toList();
        List<UnifiedHit> sourceSelected = sourceCandidates.stream().limit(sourceQuota).toList();
        int softCap = Math.max(1, carQuota / 3);
        List<UnifiedHit> softSelected = softCandidates.stream().limit(Math.min(softCap, Math.max(0, carQuota - carSelected.size()))).toList();
        List<UnifiedHit> selected = new ArrayList<>(carSelected);
        selected.addAll(softSelected);
        selected.addAll(kbSelected);
        selected.addAll(newsSelected);
        selected.addAll(sourceSelected);
        selected.sort(orderCmp);
        // 来源构成(C2:三域组合;10-05 E:NEWS 域内按 category 细分追加)
        boolean hasCar = selected.stream().anyMatch(h -> "CAR".equals(h.source()));
        boolean hasKb = selected.stream().anyMatch(h -> "KB".equals(h.source()));
        boolean hasNews = selected.stream().anyMatch(h -> "NEWS".equals(h.source()) && isBydNews(h));
        List<String> sourceParts = new ArrayList<>();
        if (hasCar) sourceParts.add("车型数据");
        if (hasKb) sourceParts.add("通用知识库");
        if (hasNews) sourceParts.add("官方新闻");
        // 10-05 E:用户采集源按 category 追加其分类名(存在才追加,保序去重)
        for (String cat : sourceCategories(selected)) {
            if (!sourceParts.contains(cat)) sourceParts.add(cat);
        }
        String sourceLine = "知识来源：" + (sourceParts.isEmpty() ? "车型数据" : String.join(" + ", sourceParts));
        StringBuilder sb = new StringBuilder();
        StringBuilder covered = new StringBuilder();
        // 10-03 E5：KB/NEWS 数值事实（增量收集，仅非 CAR 命中时出现）
        List<String> extraCoverage = new ArrayList<>();
        sb.append(sourceLine).append("\n---\n");
        for (UnifiedHit h : selected) {
            if ("KB".equals(h.source())) {
                sb.append("【通用知识：").append(h.modelName() == null ? "" : h.modelName()).append("】")
                  .append(h.chunkText()).append("\n---\n");
                extraCoverage.add(coverageSegment("通用知识", h.modelName(), h.chunkText()));
            } else if ("NEWS".equals(h.source())) {
                // 10-05 E:BYD 保持「【官方新闻：<title>】」逐字等价;用户采集源按 category 细分标注
                sb.append(sourceAnnotation(h))
                  .append(h.chunkText()).append("\n---\n");
                extraCoverage.add(coverageSegment(sourceLabel(h), h.modelName(), h.chunkText()));
            } else {
                sb.append("【车型数据：").append(h.modelName() == null ? "" : h.modelName()).append("】")
                  .append(h.chunkText()).append("\n---\n");
                covered.append(extractParamSummary(h.chunkText()));
            }
        }
        // 10-03 E5 覆盖度三域统一：CAR 保持 extractParamSummary 逐字不变；KB/NEWS 追加数值事实。
        // 仅 CAR 命中（extra 为空）时 covered 与改造前逐字等价（回归锁）。
        String extra = buildExtraCoverage(extraCoverage);
        if (!extra.isEmpty()) {
            if (covered.length() > 0) covered.append("；");
            covered.append(extra);
        }
        log.info("统一检索完成 anchors={} raw={} selected={} (car={} kb={} news={} source={}) maxScore={}",
                anchors, rawHit, selected.size(), carSelected.size(), kbSelected.size(),
                newsSelected.size(), sourceSelected.size(), maxScore);
        // R3 知识引用明细(RagResult 附带,与注入 context 同源):仅 OK 时非空;截断防超列。

        java.util.List<Citation> cites = new ArrayList<>(Math.min(selected.size(), CITE_MAX));
        for (UnifiedHit h : selected) {

            if (cites.size() >= CITE_MAX) break;
            String text = h.chunkText();
            if (text != null && text.length() > CITE_TEXT_MAX) text = text.substring(0, CITE_TEXT_MAX) + "…";
            // 10-05 E(P0 字段贯通):sourceType/category 必须随 Citation 传到 KnowledgeSearchTool,
            // 否则下游 F 拿不到来源类型判 SOURCE
            cites.add(new Citation(h.source(), h.modelName() == null ? "" : h.modelName(),
                    h.chunkType(), h.score(), text == null ? "" : text, h.docId(),
                    h.sourceType(), h.category()));
        }
        return new RagResult(RagStatus.OK, sb.toString(), rawHit, maxScore, covered.toString(), cites);
    }

    /** NEWS 域内是否 BYD 官方新闻({@code sourceType==null} 兜底为 byd-news,兼容未回填/未打标块)。 */
    static boolean isBydNews(UnifiedHit h) {
        return h.sourceType() == null || h.sourceType().isBlank() || "byd-news".equals(h.sourceType());
    }

    /** 用户采集源在「知识来源」行内追加的分类名(按 category;无分类归「信源」;保序去重)。 */
    static List<String> sourceCategories(List<UnifiedHit> selected) {
        List<String> out = new ArrayList<>();
        for (UnifiedHit h : selected) {
            if (!"NEWS".equals(h.source()) || isBydNews(h)) continue;
            String cat = (h.category() == null || h.category().isBlank()) ? "信源" : h.category().trim();
            if (!out.contains(cat)) out.add(cat);
        }
        return out;
    }

    /** NEWS 来源标注名：BYD 固定「官方新闻」；用户采集源按 category 细分（无分类归「信源」）。 */
    static String sourceLabel(UnifiedHit h) {
        if (isBydNews(h)) return "官方新闻";
        return (h.category() == null || h.category().isBlank()) ? "信源" : h.category().trim();
    }

    /**
     * NEWS 块行内来源标注(10-05 E):BYD 逐字保持「【官方新闻：&lt;name&gt;】」;
     * 用户采集源按 category 细分「【销量数据：…】/【投诉榜：…】/【政策公示：…】/其他「【信源：…】」。
     */
    static String sourceAnnotation(UnifiedHit h) {
        return "【" + sourceLabel(h) + "：" + (h.modelName() == null ? "" : h.modelName()) + "】";
    }

    /** 旧签名(S7 兼容委托):modelIds 语义变为锚点车型。 */
    public RagResult retrieveForGeneration(List<Long> modelIds, String query, int topK) {
        return retrieveForGeneration(query, topK, modelIds);
    }

    /**
     * 统一检索(S8):全库 top-K,跨车型域与 KB 域。
     * 09-15 qa-auto-illustrate:补读行内 docId（SQL 本已 SELECT 该列；域内语义随 source 变化），
     * 供问答配图经 NEWS 块定位来源新闻。**不改 SQL、不改配额与排序**。
     */
    public List<UnifiedHit> retrieveUnified(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String model = embeddingClient.modelName();
        List<UnifiedHit> hits = new ArrayList<>();
        // ① 车型 + KB:合并候选窗(C2 前语义原样保留)
        for (Document doc : store.searchDomains(List.of(VectorDomain.CAR.name(), VectorDomain.KB.name()),
                query, limit, 0, model)) {
            hits.add(toUnified(doc));
        }
        // ② 新闻域:独立候选窗,不与 CAR/KB 争抢全局窗口
        for (Document doc : store.searchDomains(List.of(VectorDomain.NEWS.name()), query, limit, 0, model)) {
            hits.add(toUnified(doc));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /**
     * 通用域检索(S7):KB 域 top-K。返回 TypedHit(chunkType 固定 "KB_CHUNK")。
     */
    public List<TypedHit> retrieveKb(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        List<Document> docs = store.searchDomains(List.of(VectorDomain.KB.name()), query, topK, 0,
                embeddingClient.modelName());
        List<TypedHit> hits = new ArrayList<>();
        for (Document doc : docs) {
            hits.add(new TypedHit(text(doc), "KB_CHUNK", doc.getScore() == null ? 0 : doc.getScore()));
        }
        return hits;
    }

    /** Document → UnifiedHit（metadata 域字段 + content + score）。 */
    private UnifiedHit toUnified(Document doc) {
        String source = metaString(doc, "domain");
        if (source == null) source = "CAR";
        Long modelId = metaLong(doc, "modelId");
        String modelName = metaString(doc, "name");
        Long docId = metaLong(doc, "refId");
        String type = metaString(doc, "chunkType");
        // 10-05 E:NEWS 域内来源类型/分类透传(P0 字段贯通起点)
        String sourceType = metaString(doc, "sourceType");
        String category = metaString(doc, "category");
        double score = doc.getScore() == null ? 0 : doc.getScore();
        return new UnifiedHit(text(doc), type == null ? "PARAM_GROUP" : type, score,
                source, modelId, modelName == null ? "" : modelName, docId, sourceType, category);
    }

    private static String text(Document doc) {
        return doc.getText() == null ? "" : doc.getText();
    }

    private static String metaString(Document doc, String key) {
        Object v = doc.getMetadata().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long metaLong(Document doc, String key) {
        Object v = doc.getMetadata().get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
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

    // ==================== 10-03 E5：覆盖度三域统一（coveredText） ====================

    /** 覆盖度清单整体长度上限（沿用既有 ~400 字口径，防灌爆 prompt）。 */
    private static final int COVERED_MAX = 400;

    /** 单条 KB/NEWS 覆盖段内数值上限（防单块大数字列占满清单）。 */
    private static final int COVERAGE_SEGMENT_MAX = 12;

    /**
     * 单块数值事实段：{@code 〔<label>：<标题>〕<数值,...>}。无标题时 {@code 〔<label>：〕}（保留冒号）；
     * 无数值返回空串（该块不产出覆盖度）。数值口径与 {@link com.sparkora.ai.NumericSignature}
     * （C7 正文数值回查/claim 归并同源）一致——1200 与 12000 不会互相误配。
     */
    static String coverageSegment(String label, String title, String chunkText) {
        List<String> nums = NumericSignature.numberValues(chunkText);
        if (nums.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("〔").append(label).append("：");
        String t = title == null ? "" : title.trim();
        sb.append(t).append("〕");
        int n = Math.min(nums.size(), COVERAGE_SEGMENT_MAX);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(nums.get(i));
        }
        return sb.toString();
    }

    /**
     * 合并 KB/NEWS 覆盖段：去重（保序）+ 整体长度上限。段间用「；」分隔，与 CAR 段落拼接时
     * 由调用方补分隔符。仅 CAR 命中时本方法不产生内容（extraCoverage 为空 → 空串）。
     */
    static String buildExtraCoverage(List<String> segments) {
        if (segments == null || segments.isEmpty()) return "";
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String seg : segments) {
            if (seg == null || seg.isEmpty() || !seen.add(seg)) continue;
            if (out.length() > 0) out.append("；");
            out.append(seg);
            if (out.length() > COVERED_MAX) { out.append("…"); break; }
        }
        return out.toString();
    }
}
