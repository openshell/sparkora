package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
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
     */
    public record SearchMeta(String strategy, String provider, String query, int resultCount, long latencyMs,
                             String fallbackReason, List<Map<String, Object>> attempts) {
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
            outcome = vertical == null
                    ? webRouter.search(appliedWebQuery, Math.min(5, webQuota), snapshot)
                    : webRouter.searchVertical(appliedWebQuery, Math.min(5, webQuota), snapshot, vertical);
            webHits = outcome.hits();
            // R1:背景题对 top 1–2 URL 调 extract 取正文并回填(失败/空/Tavily 不可用 → 保持 null 摘要降级)
            if (background && !webHits.isEmpty()) {
                webHits = enrichContent(question, webHits);
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
                ctx.append(h.title()).append(" : ").append(snippet(h.snippet())).append('\n');
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
                    searchMeta(snapshot, outcome, appliedWebQuery, webHits.size()));
        } catch (Exception e) {
            // LLM 汇总失败:降级为原始条目,但仍上报本次实际接受的 WEB 结果数(R10 口径一致:
            // webCount/resultCount 描述搜索结果,不因下游 LLM 失败而清零;降级原因改标 LLM_FALLBACK)
            log.warn("研究子代理 LLM 汇总失败,降级为原始条目 question={}: {}", question, e.getMessage());
            return new Note(question, "FALLBACK", rawFallback(hits), webHits.size(),
                    searchMeta(snapshot, outcome, appliedWebQuery, webHits.size(), true));
        }
    }

    /** 兼容重载(旧签名,无锁定答案/快照/内容描述):仅用于既有测试/调用方,行为退化为无 WEB 策略路由。 */
    public Note research(String question, List<String> toolsAllowed, int webQuota, List<Long> anchors, String topic) {
        return research(question, toolsAllowed, webQuota, anchors, topic, null, null,
                WebSearchSnapshot.of(com.sparkora.deep.search.WebProviderOrder.defaults(), false, null, 0));
    }

    /** 搜索元数据组装(无 WEB 尝试时返回 null)。 */
    private static SearchMeta searchMeta(WebSearchSnapshot snapshot, WebSearchOutcome outcome, String query, int resultCount) {
        return searchMeta(snapshot, outcome, query, resultCount, false);
    }

    /**
     * 搜索元数据组装(无 WEB 尝试时返回 null)。
     *
     * @param llmFallback 下游 LLM 汇总是否失败降级(原始条目);true 时 search 层降级原因标 {@code LLM_FALLBACK}
     *                    (provider 层 attempts 保持原样,两者失败原因不混淆)
     */
    private static SearchMeta searchMeta(WebSearchSnapshot snapshot, WebSearchOutcome outcome, String query,
                                         int resultCount, boolean llmFallback) {
        if (outcome == null) return null;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (WebSearchOutcome.Attempt a : outcome.attempts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", a.provider().name());
            m.put("resultCount", a.resultCount());
            m.put("latencyMs", a.latencyMs());
            m.put("fallbackReason", a.fallbackReason());
            m.put("ok", a.ok());
            attempts.add(m);
        }
        long latency = outcome.attempts().stream().mapToLong(WebSearchOutcome.Attempt::latencyMs).sum();
        String reason = llmFallback ? "LLM_FALLBACK" : outcome.fallbackReason();
        return new SearchMeta(snapshot == null ? null : snapshot.strategyLabel(),
                outcome.usedProvider() == null ? null : outcome.usedProvider().name(),
                query, resultCount, latency, reason, attempts);
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
                // R9:显式标注的 source.type 只能是 KB 或 WEB;其他值不可核验,拒绝并转 gap。
                if (!type.isBlank() && !"WEB".equalsIgnoreCase(type) && !"KB".equalsIgnoreCase(type)) {
                    rejected.add(gapOf(f, "来源类型不可核验(type 必须为 KB 或 WEB)"));
                    continue;
                }
                String declaredUrl = src.isObject() ? src.path("url").asText("").trim() : "";
                String declaredSid = src.isObject() ? src.path("sourceId").asText("").trim() : "";
                // R9/AC-08:携带 url 或 sourceId 的事实一律按 WEB 声明校验(sourceId 必须命中本次输入)——
                // 即使模型漏标 type 或误标 KB,其自造 URL 也不得作为可信证据进入手册。
                // KB 命中既无 url 也无 sourceId(SearchHit.kb),故缺 type+无 url/sourceId 的既有行为不受影响。
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
                    // 同时把 type 归一为 WEB:漏标/误标 KB 的 URL 事实按 WEB 计,避免 FactSheet 默认当 KB(0.9)采信。
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
               .append("\",\"docId\":").append(h.docId() == null ? 0 : h.docId())
               .append("},\"confidence\":").append("KB".equals(h.type()) ? "0.6" : "0.4");
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
    private List<WebHit> enrichContent(String question, List<WebHit> webHits) {
        try {
            List<String> urls = new ArrayList<>();
            for (WebHit wh : webHits) {
                if (wh.url() != null && !wh.url().isBlank()) urls.add(wh.url());
                if (urls.size() >= 2) break;   // 仅 top 1–2,控制 credits 与体积
            }
            if (urls.isEmpty()) return webHits;
            List<SearchTool.SearchHit> extracted = webRouter.extract(question, urls);
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
