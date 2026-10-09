package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
import com.sparkora.deep.search.WebProvider;
import com.sparkora.deep.search.WebResultNormalizer;
import com.sparkora.deep.search.WebResultNormalizer.WebHit;
import com.sparkora.deep.search.WebSearchOutcome;
import com.sparkora.deep.search.WebSearchRouter;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.deep.tool.KnowledgeSearchTool;
import com.sparkora.deep.tool.SearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究子代理执行器(S9 ③):单问题研究——查本地 KB + 可选 WEB(策略路由,默认 TAVILY_FIRST)→ LLM 汇总为研究笔记。
 *
 * <p>09-25-brief-web-search 改造:
 * <ul>
 *   <li>WEB provider 顺序不再硬编码,统一委托 {@link WebSearchRouter} + 启动时快照(同批次共用一个快照);</li>
 *   <li>WEB query 注入项目主题 + 已锁定澄清答案(未锁定的不得进入);</li>
 *   <li>LLM 上下文中每条 WEB 命中带稳定 sourceId,事实 source 必须引用本次输入的 sourceId;</li>
 *   <li>后验校验:sourceId 未知 / URL 或 provider 不匹配的事实拒绝并转 gap,不进入事实手册。</li>
 * </ul>
 * 笔记 JSON 容错:畸形 JSON 重试 1 次;仍失败降级为「工具直出原始条目」(完整 JSON 转义),不静默丢。
 */
@Slf4j
@Component
public class SubAgentRunner {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final KnowledgeSearchTool kbTool;
    private final WebSearchRouter webRouter;
    /** 深度配置(10-04 A:webVerticalNewsEnabled 决定时效题是否走 news 垂直;测试可空=强制 web)。 */
    private final com.sparkora.config.DeepProperties deepProps;

    @org.springframework.beans.factory.annotation.Autowired
    public SubAgentRunner(AiClient aiClient, ObjectMapper json,
                          KnowledgeSearchTool kbTool, WebSearchRouter webRouter,
                          com.sparkora.config.DeepProperties deepProps) {
        this.aiClient = aiClient;
        this.json = json;
        this.kbTool = kbTool;
        this.webRouter = webRouter;
        this.deepProps = deepProps;
    }

    /** 兼容构造器(无 DeepProperties:测试用;垂直强制 web,保证零回归)。 */
    public SubAgentRunner(AiClient aiClient, ObjectMapper json,
                          KnowledgeSearchTool kbTool, WebSearchRouter webRouter) {
        this(aiClient, json, kbTool, webRouter, null);
    }

    /** 研究笔记(标准化产物)。factsJson 为 {facts:[…],gaps:[…]} 字符串。 */
    public record Note(String question, String status, String factsJson, int webCount, SearchMeta search) {
        /** 兼容构造器(无搜索元数据时)。 */
        public Note(String question, String status, String factsJson, int webCount) {
            this(question, status, factsJson, webCount, null);
        }
    }

    /**
     * 搜索可观测元数据(R10):策略/实际 provider/结果数/耗时/降级原因/逐 provider attempts/query。
     * 不得记录 API 密钥。
     *
     * <p>10-04-web-fanout-merge B-R4 增量:保留 {@code provider}(首个产出命中的 provider,兼容既有前端/测试),
     * <b>新增</b> {@code providers}(本轮采信的全部 provider 列表;FIRST_HIT 为单元素/空)。保留旧 7 参构造器。
     */
    public record SearchMeta(String strategy, String provider, String query, int resultCount, long latencyMs,
                             String fallbackReason, List<Map<String, Object>> attempts,
                             List<String> providers, int dedupedCount, int cacheHit, boolean budgetExhausted) {

        /** 兼容构造器(8 参,10-04 B):10-04 C 增量字段默认 0/false。 */
        public SearchMeta(String strategy, String provider, String query, int resultCount, long latencyMs,
                          String fallbackReason, List<Map<String, Object>> attempts, List<String> providers) {
            this(strategy, provider, query, resultCount, latencyMs, fallbackReason, attempts, providers, 0, 0, false);
        }

        /** 兼容构造器(7 参):providers 由 provider 派生。 */
        public SearchMeta(String strategy, String provider, String query, int resultCount, long latencyMs,
                          String fallbackReason, List<Map<String, Object>> attempts) {
            this(strategy, provider, query, resultCount, latencyMs, fallbackReason, attempts,
                    provider == null ? List.of() : List.of(provider), 0, 0, false);
        }
    }

    /**
     * 执行单个研究问题(策略路由版,09-25)。
     *
     * <p>KB 检索:复合 query(主题 + 问题)+ 锚点加权统一通道。
     * WEB:gap 驱动——KB 已命中车型域权威块时跳过;否则按快照策略路由(Tavily 优先、SearxNG 兜底)。
     *
     * @param question     研究问题
     * @param toolsAllowed 允许的工具集(如 [KB, WEB])
     * @param webQuota     本问题 WEB 返回条数上限
     * @param anchors      锚点车型 id(项目关联/主题识别;可空)
     * @param topic        项目主题(复合 query 语料;可空)
     * @param lockedAnswers 已锁定的澄清答案(用于构造更具体的 WEB query;未锁定不得进入)
     * @param contentDescription 项目内容描述(10-02 R4b:仅进入 LLM 汇总上下文,让 AI 理解写作意图;
     *                            <b>不进入</b> {@link #compositeQuery}/{@link #webQuery},保持检索语料纯净,Q4=A)
     * @param snapshot     本次研究的策略与开关快照(启动时解析一次,同批次共享)
     */
    public Note research(String question, List<String> toolsAllowed, int webQuota, List<Long> anchors, String topic,
                         String lockedAnswers, String contentDescription, WebSearchSnapshot snapshot) {
        return researchInternal(question, toolsAllowed, webQuota, anchors, topic, lockedAnswers, contentDescription,
                snapshot, snapshot == null ? null : snapshot.batch());
    }

    /**
     * 执行单个研究问题(策略路由版,09-25;10-04 C 内部:批次上下文由快照携带)。
     *
     * <p>{@code batch} 非空时:WEB 搜索经批次预算(计量源 Tavily/Serper 限额)+ 批次内缓存 + 跨轮去重
     * (Round 2 起启用);空时逐位等价旧行为(零回归)。
     */
    private Note researchInternal(String question, List<String> toolsAllowed, int webQuota, List<Long> anchors,
                                  String topic, String lockedAnswers, String contentDescription,
                                  WebSearchSnapshot snapshot, com.sparkora.deep.search.WebBatchContext batch) {
        List<SearchTool.SearchHit> hits = new ArrayList<>();
        // 1) 本地 KB(锚点加权,受设置门控):复合语料 = 主题(含车型名) + 研究问题
        if (toolsAllowed.contains("KB")) {
            String kbQuery = compositeQuery(topic, question);
            try {
                hits.addAll(kbTool.search(kbQuery, 8, anchors));
            } catch (Exception e) {
                log.warn("KB 工具调用失败 question={}: {}", question, e.getMessage());
            }
        }
        // 2) WEB(策略路由;额度受控):gap 驱动——KB 已命中车型域权威块时不再全问题重搜,
        //    仅在 KB 无命中时补查(WEB 结果只补缺口,不覆盖 KB 结论;冲突裁决在 FactSheetService.merge)
        List<WebHit> webHits = List.of();
        WebSearchOutcome outcome = null;
        String appliedWebQuery = null;
        boolean kbParamAuthoritative = hits.stream().anyMatch(h ->
                "KB".equals(h.type()) && (h.title() != null && h.title().contains("MODEL_INFO")
                        || (h.snippet() != null && h.snippet().contains("价格区间"))));
        // R2(09-27-brief-writing-linkage-fix):KB 权威块仅对「事实/参数型」问题生效。
        // 背景/来龙去脉型问题在车型锚定主题下几乎必然命中该车型 MODEL_INFO(复合 query 带锚点加权),
        // 若据此跳过 WEB,行业战略类背景素材永远拿不到(KB 是车型库,不含此类内容)。故背景题强制放行 WEB。
        // R1/R3(09-27-tavily-extract-kind-hypotheses):背景题额外对 top URL 补抓正文(仅背景题注入)。
        boolean background = ResearchPlannerService.isBackgroundQuestion(question);
        boolean kbAuthoritative = !background && kbParamAuthoritative;
        if (toolsAllowed.contains("WEB") && snapshot != null && snapshot.webAllowed() && webQuota > 0 && !kbAuthoritative) {
            appliedWebQuery = webQuery(topic, question, lockedAnswers);
            // 10-04-serper-provider A-R3:时效题走 news 垂直(仅 Serper 支持;开关关闭时强制 web→零回归)。
            // background 参数型/参数题仍走 web:参数事实通常非时效问题(§5.1)。
            String vertical = resolveVertical(question);
            // batch==null 走 3 参重载(既有行为/测试桩逐位等价);非空走 4 参(预算/缓存/去重)
            outcome = vertical == null
                    ? (batch == null ? webRouter.search(appliedWebQuery, Math.min(5, webQuota), snapshot)
                    : webRouter.search(appliedWebQuery, Math.min(5, webQuota), snapshot, batch))
                    : (batch == null ? webRouter.searchVertical(appliedWebQuery, Math.min(5, webQuota), snapshot, vertical)
                    : webRouter.searchVertical(appliedWebQuery, Math.min(5, webQuota), snapshot, vertical, batch));
            webHits = outcome.hits();
            // R1:背景题对 top 1–2 URL 调 extract 取正文并回填(失败/空/Tavily 不可用 → 保持 null 摘要降级)
            if (background && !webHits.isEmpty()) {
                webHits = enrichContent(question, webHits, snapshot);
            }
            for (WebHit wh : webHits) hits.add(wh.toSearchHit());
        }
        // 3) LLM 汇总为结构化笔记(容错:非法 JSON 重试 1 次;仍失败走原始条目降级)
        try {
            // 双关(KB/WEB 均被全局设置停用)时明确告知无外部资料,要求 gaps 标注,不臆造
            boolean noExternalSources = toolsAllowed.isEmpty();
            // C1:固定指令外置模板;双关降级说明经 {{noSourcesRule}} 变量注入(空串=不追加,逐字等价旧分支)。
            // C2:{{schema}} 由 SubAgentFactsDto 类型派生(单一来源),prompt 不再内联 JSON schema 字面量。
            String system = com.sparkora.ai.PromptTemplateLoader.render("deep/subagent-system.st",
                    java.util.Map.of("noSourcesRule", noExternalSources
                            ? com.sparkora.ai.PromptTemplateLoader.render("deep/subagent-nosources.st", java.util.Map.of())
                            : "",
                            "schema", AiClient.jsonSchema(com.sparkora.ai.SubAgentFactsDto.class)));
            // 10-02 R4b/Q4=A:内容描述仅作为「写作意图」注入 LLM 汇总上下文(置于研究问题之前),
            // 让 AI 理解写作方向;不改 compositeQuery/webQuery(检索语料保持纯净,不引入噪声)。
            StringBuilder ctx = new StringBuilder();
            if (contentDescription != null && !contentDescription.isBlank()) {
                ctx.append("写作意图/内容描述:").append(contentDescription).append('\n');
            }
            ctx.append("研究问题:").append(question).append("\n检索结果:\n");
            for (SearchTool.SearchHit h : hits) {
                ctx.append("- [").append(h.type()).append("] ");
                if (h.sourceId() != null && !h.sourceId().isBlank()) ctx.append("sourceId=").append(h.sourceId()).append(' ');
                if (h.url() != null && !h.url().isBlank()) ctx.append(h.url()).append(" | ");
                ctx.append(h.title()).append(" : ").append(snippet(h.snippet()));
                // 10-05-source-web-fusion F-R4/F-R8:本地自建信源(SOURCE)把 sourceType/authorityTier/crossCounted
                // 原样透出到 ctx,供 LLM 回填 fact.source——这是本地信源元数据到达 FactSheetService 的主路径
                // (rawFallback 仅在 LLM 降级时兜底)。非 SOURCE/字段为空时不追加(零回归)。
                appendSourceMeta(ctx, h);
                ctx.append('\n');
                // R3(09-27-tavily-extract-kind-hypotheses):仅背景型问题注入正文片段(参数题只用摘要);
                // 正文已在工具层按 DEEP_WEB_CONTENT_MAX_CHARS 截断(唯一上限、单点化),此处不再二次截断。
                if (background && h.content() != null && !h.content().isBlank()) {
                    ctx.append("  正文片段:").append(h.content()).append('\n');
                }
            }
            if (hits.isEmpty()) ctx.append("(无检索结果,请基于空结果产出 gaps)\n");
            String factsJson = validateFacts(chat(system, ctx.toString()), webHits);
            long webCount = webHits.size();   // R10/webCount 语义:实际接受的 WEB 结果数(非事实条数)
            return new Note(question, "DONE", factsJson, (int) webCount,
                    searchMeta(snapshot, outcome, appliedWebQuery, webHits.size(), false, batch));
        } catch (Exception e) {
            // LLM 汇总失败:降级为原始条目,但仍上报本次实际接受的 WEB 结果数(R10 口径一致:
            // webCount/resultCount 描述搜索结果,不因下游 LLM 失败而清零;降级原因改标 LLM_FALLBACK)
            log.warn("研究子代理 LLM 汇总失败,降级为原始条目 question={}: {}", question, e.getMessage());
            return new Note(question, "FALLBACK", rawFallback(hits), webHits.size(),
                    searchMeta(snapshot, outcome, appliedWebQuery, webHits.size(), true, batch));
        }
    }

    /** 兼容重载(旧签名,无锁定答案/快照/内容描述):仅用于既有测试/调用方,行为退化为无 WEB 策略路由。 */
    public Note research(String question, List<String> toolsAllowed, int webQuota, List<Long> anchors, String topic) {
        return research(question, toolsAllowed, webQuota, anchors, topic, null, null,
                WebSearchSnapshot.of(com.sparkora.deep.search.WebProviderOrder.defaults(), false, null, 0));
    }

    /**
     * Round 2 定向补检索(10-04-web-followup-budget C-R2):仅 WEB 多源搜索 + ≤1 次 LLM 抽取。
     *
     * <p>与 {@link #research} 的差异:不查 KB(补检索目标是世界事实的交叉验证/缺口);
     * query 由调用方用 {@link #webQuery} 确定性拼装(主题 + 目标 claim + 已锁定答案);默认走 {@code web} 垂直
     * (参数型事实通常非时效问题)。跨轮去重与预算由 {@code batch} 统一治理(Round 2 阶段已开启去重)。
     * 异常隔离:内部捕获返回 {@code null}(调用方跳过该目标,降级不阻断)。
     *
     * @param targetClaim 目标 claim(用于 ctx 与事实抽取上下文)
     * @param query       已拼装的 WEB query
     */
    public Note researchFollowup(String targetClaim, String query, List<Long> anchors, String topic,
                                 String contentDescription, WebSearchSnapshot snapshot,
                                 com.sparkora.deep.search.WebBatchContext batch) {
        if (snapshot == null || !snapshot.webAllowed()) return null;
        try {
            // C-R2:补检索固定走 PRIMARY_FANOUT 多源交叉(与部署级 first_hit 无关——单源补检索无交叉价值,
            // 白白消耗预算);primary 全空时仍按 fanout 内部逻辑回落 fallback,不降可用性。
            WebSearchSnapshot fanoutSnap = snapshot.withStrategy(com.sparkora.deep.search.SearchStrategy.PRIMARY_FANOUT);
            WebSearchOutcome outcome = webRouter.search(query, 5, fanoutSnap, batch);
            List<WebHit> webHits = outcome.hits();
            if (webHits.isEmpty()) return null;   // 空结果:跳过该目标(不写回空证据)
            List<SearchTool.SearchHit> hits = new ArrayList<>();
            for (WebHit wh : webHits) hits.add(wh.toSearchHit());
            String system = com.sparkora.ai.PromptTemplateLoader.render("deep/subagent-system.st",
                    java.util.Map.of("noSourcesRule", "",
                            "schema", AiClient.jsonSchema(com.sparkora.ai.SubAgentFactsDto.class)));
            StringBuilder ctx = new StringBuilder();
            if (contentDescription != null && !contentDescription.isBlank()) {
                ctx.append("写作意图/内容描述:").append(contentDescription).append('\n');
            }
            ctx.append("补检索目标:").append(targetClaim).append("\n检索结果:\n");
            for (SearchTool.SearchHit h : hits) {
                ctx.append("- [").append(h.type()).append("] ");
                if (h.sourceId() != null && !h.sourceId().isBlank()) ctx.append("sourceId=").append(h.sourceId()).append(' ');
                if (h.url() != null && !h.url().isBlank()) ctx.append(h.url()).append(" | ");
                ctx.append(h.title()).append(" : ").append(snippet(h.snippet())).append('\n');
            }
            String factsJson = validateFacts(chat(system, ctx.toString()), webHits);
            return new Note(targetClaim, "DONE", factsJson, webHits.size(),
                    searchMeta(fanoutSnap, outcome, query, webHits.size(), false, batch));
        } catch (Exception e) {
            // 补检索失败降级:调用方跳过该目标,Round 1 产物不受影响
            log.warn("Round 2 补检索子代理失败 target={}: {}", targetClaim, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 搜索元数据组装(无 WEB 尝试时返回 null)。 */
    private static SearchMeta searchMeta(WebSearchSnapshot snapshot, WebSearchOutcome outcome, String query,
                                         int resultCount) {
        return searchMeta(snapshot, outcome, query, resultCount, false, null);
    }

    /**
     * 搜索元数据组装(无 WEB 尝试时返回 null)。
     *
     * @param llmFallback 下游 LLM 汇总是否失败降级(原始条目);true 时 search 层降级原因标 {@code LLM_FALLBACK}
     *                    (provider 层 attempts 保持原样,两者失败原因不混淆)
     * @param batch       批次上下文(10-04 C:透出 dedupedCount/cacheHit/budgetExhausted;可空)
     */
    private static SearchMeta searchMeta(WebSearchSnapshot snapshot, WebSearchOutcome outcome, String query,
                                         int resultCount, boolean llmFallback,
                                         com.sparkora.deep.search.WebBatchContext batch) {
        if (outcome == null) return null;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (WebSearchOutcome.Attempt a : outcome.attempts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", a.provider().name());
            m.put("resultCount", a.resultCount());
            m.put("latencyMs", a.latencyMs());
            m.put("fallbackReason", a.fallbackReason());
            m.put("ok", a.ok());
            // 10-04 B-R5:交叉验证观测(该 provider 命中中已被其他 provider 见证的条数)
            m.put("witnessTotal", a.witnessTotal());
            // 10-05-tavily-endpoint-priority:多端点 provider 实际端点(relay/official);单端点/未知不写
            if (a.usedEndpoint() != null && !a.usedEndpoint().isBlank()) {
                m.put("usedEndpoint", a.usedEndpoint());
            }
            attempts.add(m);
        }
        long latency = outcome.attempts().stream().mapToLong(WebSearchOutcome.Attempt::latencyMs).sum();
        String reason = llmFallback ? "LLM_FALLBACK" : outcome.fallbackReason();
        List<String> providers = new ArrayList<>();
        for (WebProvider p : outcome.usedProviders()) providers.add(p.name());
        // 10-04 C-R8:预算/去重/缓存增量观测(B 的 providers/attempts 语义不变)
        int deduped = batch == null ? 0 : batch.dedupedCount();
        int cacheHit = batch == null ? 0 : batch.cache().hits();
        boolean budgetExhausted = batch != null && batch.budget().budgetExhausted();
        return new SearchMeta(snapshot == null ? null : snapshot.strategyLabel(),
                outcome.usedProvider() == null ? null : outcome.usedProvider().name(),
                query, resultCount, latency, reason, attempts, providers, deduped, cacheHit, budgetExhausted);
    }

    /**
     * WEB 查询构造(R7):项目主题 + 研究问题 + 已锁定澄清答案(仅非空答案);
     * 未锁定的澄清项绝不进入 query。无有效组成时回退问题本身。
     *
     * <p>R5(09-27-brief-writing-linkage-fix):语义为「放弃/无偏好」的否定性答案
     * (如「不对比」「无所谓」)不得进入 query——它们描述的是用户的不选择,拼入会制造搜索噪声。
     */
    static String webQuery(String topic, String question, String lockedAnswersJson) {
        StringBuilder sb = new StringBuilder();
        String t = topic == null ? "" : topic.trim();
        if (!t.isEmpty()) sb.append(t);
        String q = question == null ? "" : question.trim();
        if (!q.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(q);
        for (String a : lockedAnswerValues(lockedAnswersJson)) {
            if (isNegativeAnswer(a)) continue;   // R5:否定性答案不注入
            if (!a.isBlank()) sb.append(sb.length() > 0 ? " " : "").append(a.trim());
        }
        return sb.length() == 0 && question != null ? question : sb.toString();
    }

    /** R5:语义为「放弃/无偏好」的否定性答案值(精确匹配 + 「不对比/不需要」前缀兜底)。 */
    private static final List<String> NEGATIVE_ANSWER_VALUES = List.of(
            "不对比", "不比较", "无所谓", "都可以", "都行", "不限", "无偏好", "随便",
            "暂无", "不需要", "无", "没有", "不涉及", "跳过");

    /** R5:否定性答案判定(仅精确匹配词表 + 「不对比/不需要」前缀,不做模糊包含,避免误伤正常答案)。 */
    static boolean isNegativeAnswer(String a) {
        if (a == null) return false;
        String s = a.trim();
        return NEGATIVE_ANSWER_VALUES.stream().anyMatch(s::equals)
                || s.startsWith("不对比") || s.startsWith("不需要");
    }

    /** 从锁定答案 JSON [{q,a}] 提取非空答案值(去重);解析失败返回空列表(未锁定/无答案)。 */
    static List<String> lockedAnswerValues(String lockedAnswersJson) {
        List<String> out = new ArrayList<>();
        if (lockedAnswersJson == null || lockedAnswersJson.isBlank()) return out;
        try {
            JsonNode arr = PLAIN_JSON.readTree(lockedAnswersJson);
            if (!arr.isArray()) return out;
            for (JsonNode n : arr) {
                String a = n.path("a").asText("").trim();
                if (!a.isBlank() && !out.contains(a)) out.add(a);
            }
        } catch (Exception ignored) {
            // 未锁定/格式异常:不注入任何答案
        }
        return out;
    }

    /** 静态解析用 ObjectMapper(仅解析锁定答案,与注入实例用途隔离)。 */
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper();

    /**
     * 事实后验校验(R9):仅接受引用了本次输入 sourceId 且 URL/provider 匹配的 WEB 事实;
     * 未知 sourceId / URL 不匹配 / provider 不匹配 → 从 facts 剔除并转 gap(不整条 agent 失败)。
     */
    String validateFacts(String factsJson, List<WebHit> webHits) {
        try {
            JsonNode root = json.readTree(factsJson);
            if (!root.isObject()) return factsJson;
            Map<String, WebHit> byId = new LinkedHashMap<>();
            for (WebHit wh : webHits) byId.put(wh.sourceId(), wh);
            ArrayNode facts = json.createArrayNode();
            List<String> rejected = new ArrayList<>();
            for (JsonNode f : root.path("facts")) {
                JsonNode src = f.path("source");
                String type = src.isObject() ? src.path("type").asText("") : "";
                // R9:显式标注的 source.type 只能是 KB / WEB / SOURCE;其他值不可核验,拒绝并转 gap。
                // 10-05-source-web-fusion F-R1:白名单扩 SOURCE(本地自建信源);核验语义见下。
                if (!type.isBlank() && !"WEB".equalsIgnoreCase(type)
                        && !"KB".equalsIgnoreCase(type) && !"SOURCE".equalsIgnoreCase(type)) {
                    rejected.add(gapOf(f, "来源类型不可核验(type 必须为 KB/WEB/SOURCE)"));
                    continue;
                }
                String declaredUrl = src.isObject() ? src.path("url").asText("").trim() : "";
                String declaredSid = src.isObject() ? src.path("sourceId").asText("").trim() : "";
                // R9/AC-08:携带 url 或 sourceId 的事实一律按 WEB 声明校验(sourceId 必须命中本次输入)——
                // 即使模型漏标 type 或误标 KB/SOURCE,其自造 URL 也不得作为可信证据进入手册。
                // KB/SOURCE 本地命中既无 url 也无 sourceId(SearchHit.kb/source),故缺 type+无 url/sourceId
                // 的既有行为不受影响;SOURCE 无 url 且无 sourceId 时直接接受(与 KB 事实同一路径)。
                boolean webClaim = "WEB".equalsIgnoreCase(type) || !declaredUrl.isBlank() || !declaredSid.isBlank();
                if (webClaim) {
                    WebHit hit = byId.get(declaredSid);
                    if (hit == null) {
                        rejected.add(gapOf(f, "引用了未知或缺失的 sourceId:" + (declaredSid.isBlank() ? "(空)" : declaredSid)));
                        continue;
                    }
                    String url = declaredUrl;
                    if (!url.isBlank() && !url.equals(hit.url())) {
                        rejected.add(gapOf(f, "URL 与 sourceId " + declaredSid + " 不匹配"));
                        continue;
                    }
                    String provider = src.path("provider").asText("");
                    if (provider.isBlank()) provider = src.path("modelName").asText("");
                    if (!provider.isBlank() && !provider.equalsIgnoreCase(hit.provider())) {
                        rejected.add(gapOf(f, "provider 与 sourceId " + declaredSid + " 不匹配"));
                        continue;
                    }
                    // 规范化:回填权威 url/provider,保证手册来源可回溯到真实命中。
                    // 同时把 type 归一为 WEB:漏标或误标 KB/SOURCE 的 URL 事实按 WEB 计,
                    // 避免 FactSheet 默认当 KB(0.9)或 SOURCE(本地权威)采信其自造 URL。
                    ObjectNode fixed = (ObjectNode) f.deepCopy();
                    ObjectNode fixedSrc = (ObjectNode) fixed.path("source");
                    fixedSrc.put("type", "WEB");
                    fixedSrc.put("url", hit.url());
                    fixedSrc.put("provider", hit.provider());
                    if (fixedSrc.path("modelName").asText("").isBlank()) fixedSrc.put("modelName", hit.provider());
                    facts.add(fixed);
                    continue;
                }
                facts.add(f);
            }
            ArrayNode gaps = json.createArrayNode();
            for (JsonNode g : root.path("gaps")) gaps.add(g);
            for (String r : rejected) gaps.add(r);
            ObjectNode out = json.createObjectNode();
            out.set("facts", facts);
            out.set("gaps", gaps);
            return json.writeValueAsString(out);
        } catch (Exception e) {
            // 解析失败不强行改写(交由上层 rawFallback 降级);保持原样,避免掩盖真实产物
            log.warn("事实后验校验解析失败,原样保留: {}", e.getMessage());
            return factsJson;
        }
    }

    /** 被拒事实转 gap 文案(截断 claim,避免超长)。 */
    private static String gapOf(JsonNode fact, String reason) {
        String claim = fact.path("claim").asText("");
        if (claim.length() > 60) claim = claim.substring(0, 60) + "…";
        return "已剔除无可靠来源的 WEB 事实「" + claim + "」:" + reason;
    }

    /** 复合 KB 检索语料:主题(通常含车型名) + 研究问题。 */
    private static String compositeQuery(String topic, String question) {
        String t = topic == null ? "" : topic.trim();
        String q = question == null ? "" : question.trim();
        if (t.isEmpty()) return q;
        if (q.isEmpty()) return t;
        if (t.contains(q) || q.contains(t)) return t.length() >= q.length() ? t : q;
        return t + ", " + q;
    }

    /**
     * 10-04-serper-provider A-R3:垂直路由决策。
     *
     * <p>时效题(命中 {@link ResearchPlannerService#isTimeSensitiveQuestion})且开关开启 → {@code news};
     * 其余(含开关关闭/无 DeepProperties 的测试路径)→ {@code null}(走既有 search,零回归)。
     * 参数型事实即使命中时效词也非时效需求,但判定成本低且 news 与 search 同价,不做额外区分。
     */
    private String resolveVertical(String question) {
        if (deepProps == null || !deepProps.isWebVerticalNewsEnabled()) return null;
        return ResearchPlannerService.isTimeSensitiveQuestion(question) ? "news" : null;
    }

    /**
     * LLM 汇总(R4,09-26 / C2):首次 2048;任何失败(截断 finish_reason=length / 空内容 / 序列化失败)
     * 均提额至 4096 重试一次,仅重试仍失败才向上抛出(→ research catch → FALLBACK)。
     * 净调用上限仍为 2 次/agent(与原「非法 JSON 重试」同量),仅重试额度提高并覆盖截断场景。
     *
     * <p>C2:schema 由 {@link SubAgentFactsDto} 类型单一派生,{@code structured} 内 validateSchema
     * 自纠错字段/类型/多余字段错误;截断仍由外层提额重试兜底。产出序列化为等价 JSON 字符串,
     * 供 {@link #validateFacts} 后验校验(字段语义不变)。
     */
    private String chat(String system, String user) throws Exception {
        try {
            return serialize(aiClient.structured(system, user, 2048, com.sparkora.ai.SubAgentFactsDto.class).entity(), null);
        } catch (Exception first) {
            log.warn("子代理汇总首次失败,提额重试(4096): {}", first.getMessage());
            return serialize(aiClient.structured(system,
                    user + "\n注意:上次输出失败(可能被截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象。", 4096,
                    com.sparkora.ai.SubAgentFactsDto.class).entity(), first);
        }
    }

    /**
     * DTO 序列化为 JSON 字符串供后续 {@link #validateFacts} 消费;失败抛 {@code AiException}
     * (携带 cause 供上层定位)。{@code @JsonInclude(NON_NULL)} 保证空字段不出现,与旧解析容错语义等价。
     */
    private String serialize(com.sparkora.ai.SubAgentFactsDto dto, Exception cause) throws Exception {
        try {
            return json.writeValueAsString(dto);
        } catch (Exception e) {
            throw new com.sparkora.ai.AiException("子代理汇总输出序列化失败: " + e.getMessage(),
                    cause == null ? e : cause);
        }
    }

    /**
     * 10-05-source-web-fusion F-R4/F-R8:把本地自建信源(SOURCE)的 {@code sourceType/authorityTier/crossCounted}
     * 以稳定文本追加到研究 ctx,供 LLM 按提示词原样回填到 {@code fact.source}——这是这些元数据到达
     * {@code FactSheetService} 的主路径({@link #rawFallback} 只在 LLM 降级时兜底)。
     *
     * <p>仅 {@code SOURCE} 命中且字段非空时追加;KB/WEB 或字段缺失不追加(旧 ctx 逐字等价,零回归)。
     * 格式:{@code sourceType=user-source crossCounted=false}(值经 JSON 转义,防注入畸形输出)。
     */
    private static void appendSourceMeta(StringBuilder ctx, SearchTool.SearchHit h) {
        if (h == null || !"SOURCE".equalsIgnoreCase(h.type())) return;
        StringBuilder meta = new StringBuilder();
        if (h.sourceType() != null && !h.sourceType().isBlank()) {
            meta.append("sourceType=").append(esc(h.sourceType()));
        }
        if (h.authorityTier() != null && !h.authorityTier().isBlank()) {
            if (meta.length() > 0) meta.append(' ');
            meta.append("authorityTier=").append(esc(h.authorityTier()));
        }
        if (h.crossCounted() != null) {
            if (meta.length() > 0) meta.append(' ');
            meta.append("crossCounted=").append(h.crossCounted());
        }
        if (meta.length() > 0) ctx.append(" (").append(meta).append(')');
    }

    /** 降级:检索命中直转原始条目(不经 LLM);JSON 转义完整(WEB 带 sourceId/provider)。 */
    static String rawFallback(List<SearchTool.SearchHit> hits) {
        StringBuilder raw = new StringBuilder("{\"facts\":[");
        boolean first = true;
        for (SearchTool.SearchHit h : hits) {
            if (!first) raw.append(',');
            first = false;
            // R1(09-26):保留命中正文 snippet 作为该条目的证据——关键背景(如「年内 2 万座」)常写在
            // snippet 里,旧实现只取 title 会让降级路径丢失素材。转义完整,JSON 仍合法。
            raw.append("{\"claim\":\"").append(esc(h.title()))
               .append("\",\"snippet\":\"").append(esc(snippet(h.snippet())))
               .append("\",\"source\":{\"type\":\"")
               .append(h.type()).append("\",\"sourceId\":\"").append(esc(h.sourceId()))
               .append("\",\"url\":\"").append(esc(h.url()))
               .append("\",\"provider\":\"").append(esc(h.provider()))
               .append("\",\"modelName\":\"").append(esc(h.modelName() == null ? "" : h.modelName()))
               .append("\",\"docId\":").append(h.docId() == null ? 0 : h.docId());
            // 10-05-source-web-fusion F-R8:SOURCE 命中透传 sourceType/authorityTier/crossCounted,
            // 供降级路径下 FactSheetService 仍能判来源身份/权威档/独立交叉;非空才写(旧契约零回归)。
            if (h.sourceType() != null && !h.sourceType().isBlank()) {
                raw.append(",\"sourceType\":\"").append(esc(h.sourceType())).append("\"");
            }
            if (h.authorityTier() != null && !h.authorityTier().isBlank()) {
                raw.append(",\"authorityTier\":\"").append(esc(h.authorityTier())).append("\"");
            }
            if (h.crossCounted() != null) {
                raw.append(",\"crossCounted\":").append(h.crossCounted());
            }
            raw.append("},\"confidence\":").append("KB".equals(h.type()) ? "0.6" : "0.4");
            // R2(09-27-tavily-extract-kind-hypotheses 增量):正文片段仅非空时写入(转义完整),
            // 与 snippet 语义区分;不写时不出现该字段(旧契约零回归)。正文已由工具层截断,不再二次截断。
            if (h.content() != null && !h.content().isBlank()) {
                raw.append(",\"content\":\"").append(esc(h.content())).append("\"");
            }
            raw.append('}');
        }
        raw.append("],\"gaps\":[\"研究汇总失败,以下为原始检索条目,请人工核对\"]}");
        return raw.toString();
    }

    /**
     * R1(09-27-tavily-extract-kind-hypotheses):背景题对 top 1–2 WEB 命中 URL 调
     * {@link WebSearchRouter#extract} 取正文,并按规范化 URL 回填对应命中的 content
     * (extract 结果的 URL 可能与命中 URL 有 fragment/大小写差异,用 normalizeUrl 对齐)。
     *
     * <p>抽取失败/空/不支持 → 原样返回(命中保持 content=null = 降级回摘要),绝不抛出。
     */
    private List<WebHit> enrichContent(String question, List<WebHit> webHits, WebSearchSnapshot snapshot) {
        try {
            List<String> urls = new ArrayList<>();
            for (WebHit wh : webHits) {
                if (wh.url() != null && !wh.url().isBlank()) urls.add(wh.url());
                if (urls.size() >= 2) break;   // 仅 top 1–2,控制 credits 与体积
            }
            if (urls.isEmpty()) return webHits;
            // 10-04 C-R7:批次存在时 extract 按快照 order + 尊重 webAllowed(webAllowed=false → 空 → 降级回摘要);
            // 无批次(旧调用方/单测)时走 2 参重载,行为逐位等价旧实现(零回归)。
            boolean batchMode = snapshot != null && snapshot.batch() != null;
            List<SearchTool.SearchHit> extracted = batchMode
                    ? webRouter.extract(question, urls, snapshot)
                    : webRouter.extract(question, urls);
            if (extracted.isEmpty()) return webHits;
            Map<String, String> byUrl = new LinkedHashMap<>();
            for (SearchTool.SearchHit e : extracted) {
                if (e == null || e.url() == null || e.content() == null || e.content().isBlank()) continue;
                String key = WebResultNormalizer.normalizeUrl(e.url());
                if (key != null) byUrl.putIfAbsent(key, e.content());
            }
            if (byUrl.isEmpty()) return webHits;
            List<WebHit> out = new ArrayList<>(webHits.size());
            for (WebHit wh : webHits) {
                String key = WebResultNormalizer.normalizeUrl(wh.url());
                String content = key == null ? null : byUrl.get(key);
                if (content == null || content.isBlank()) {
                    out.add(wh);
                } else {
                    out.add(new WebHit(wh.sourceId(), wh.title(), wh.url(), wh.snippet(), wh.provider(),
                            content));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("背景题正文补抓失败(降级回摘要) question={}: {}", question, e.getClass().getSimpleName());
            return webHits;
        }
    }

    /**
     * 正文上限单点化(09-27-tavily-extract-kind-hypotheses 已确认决策):截断唯一发生在工具层
     * ({@code TavilySearchTool.extract} 按 `DEEP_WEB_CONTENT_MAX_CHARS` 截断)。子代理只做注入/留证,
     * <b>不再二次截断</b>——否则配置值 > 默认 2000 时会被静默截回 2000,形成「工具截一次、注入再截一次」
     * 的隐形双重限制(与 design.md §4 冲突)。
     */
    private static String snippet(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }

    /** JSON 字符串转义:反斜杠优先,再引号/换行/制表/回车与其余控制字符(防 rawFallback 产出非法 JSON)。 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
