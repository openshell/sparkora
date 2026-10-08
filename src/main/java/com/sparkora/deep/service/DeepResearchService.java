package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import com.sparkora.deep.search.WebProviderOrder;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 深度研究编排(S9 ③④):并行派生子代理研究 → 汇总事实手册 → 落库 research_notes/fact_sheet。
 *
 * 异步 + 逐 agent 落库(设计 6.1/6.3,09-26 实时回写改造):
 *  - run() 同步校验 + 落全 PENDING 占位 research_notes → 立即返回(202 语义),后台 self.runAsync 执行;
 *  - runAsync() 启动阶段一次性把全部 agent 置 RUNNING(早于 submit),随后每个 agent 由**独立收集器**
 *    (虚拟线程)在各自 future 完成/超时/异常时立即回写 DONE/FALLBACK/FAILED——谁先完成谁先落库,天然乱序,
 *    不再被慢的 future[0] 阻塞;前端 2s 轮询即可看到多 agent 交错推进,消除 PENDING→DONE 瞬变。
 *  - 逐 agent 回写经 per-brief 锁串行化,保证并发收集器「读-改-写」不丢字段。
 *  - 单子代理超时/失败不阻断(缺口进手册);调用次数受 maxAgents 约束。
 */
@Slf4j
@Service
public class DeepResearchService {

    private final ArticleBriefMapper briefMapper;
    private final SubAgentRunner subAgent;
    private final FactSheetService factSheet;
    private final ObjectMapper json;
    private final com.sparkora.config.DeepProperties props;
    /** 研究完成自动生成简报(S9 修复):深度链路必须产出简报页面 */
    private final com.sparkora.service.BriefService briefService;
    /** 锚点车型解析(R1):项目关联车型 + 主题识别兜底 */
    private final com.sparkora.service.ArticleProjectCarService carService;
    private final com.sparkora.car.service.CarModelMatcherService matcherService;
    /** 项目详情(锚点兜底识别/主题语料) */
    private final com.sparkora.mapper.ArticleProjectMapper projectMapper;
    /** 系统检索设置开关(09-09-brief-gen-redesign R3):门控子代理工具装配 */
    private final com.sparkora.service.SettingService settingService;
    /** 同一 brief 运行互斥(09-25):重复 /run 返回 409,避免重复付费外部调用 */
    private final java.util.Set<Long> runningBriefs = ConcurrentHashMap.newKeySet();
    /**
     * research_notes 逐 agent 回写的 per-brief 锁(09-26 实时回写):收集器并发调用 updateAgent 时
     * 串行化「读整段 JSON → 改指定 agentId → 写回」;不同 brief 互不阻塞。批次结束清理,避免无界增长。
     */
    private final java.util.Map<Long, Object> notesLocks = new ConcurrentHashMap<>();
    // 自注入代理,确保 @Async 生效(run 内 this.runAsync 不会走代理)
    @Autowired
    @Lazy
    private DeepResearchService self;

    public DeepResearchService(ArticleBriefMapper briefMapper, SubAgentRunner subAgent,
                               FactSheetService factSheet, ObjectMapper json,
                               com.sparkora.config.DeepProperties props,
                               com.sparkora.service.BriefService briefService,
                               com.sparkora.service.ArticleProjectCarService carService,
                               com.sparkora.car.service.CarModelMatcherService matcherService,
                               com.sparkora.mapper.ArticleProjectMapper projectMapper,
                               com.sparkora.service.SettingService settingService) {
        this.briefMapper = briefMapper;
        this.subAgent = subAgent;
        this.factSheet = factSheet;
        this.json = json;
        this.props = props;
        this.briefService = briefService;
        this.carService = carService;
        this.matcherService = matcherService;
        this.projectMapper = projectMapper;
        this.settingService = settingService;
    }

    /**
     * 启动研究(同步校验 + 落 PENDING 占位,立即返回;后台异步执行)。
     *
     * <p>09-25-brief-web-search 前置校验与快照:
     * <ul>
     *   <li>brief 存在 + 属于路径 projectId + gen_mode=DEEP + 计划就绪 + 澄清已完成,否则明确 4xx;</li>
     *   <li>解析一次有效策略与开关快照(全局配置层),同批次全部子代理共用,启动后设置变更不影响;</li>
     *   <li>同一 brief 运行互斥:重复 /run 返回 409。</li>
     * </ul>
     *
     * <p>C2(10-03-gen-cognitive-redesign):新流程澄清完成以 {@code task_brief} 表达(不再落
     * {@code clarify_answers});前置放宽为「research_plan 非空 + (task_brief 非空 或 旧 clarify_answers 非空)」,
     * 兼容历史数据。PLANNING(异步生成中)仍明确拒绝。
     *
     * @return {briefId, agents, started:true}
     */
    public Map<String, Object> run(Long projectId, Long briefId) throws Exception {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        if (projectId != null && !projectId.equals(b.getProjectId())) {
            throw new IllegalArgumentException("brief 不属于该项目");
        }
        if (!"DEEP".equalsIgnoreCase(b.getGenMode())) {
            throw new IllegalArgumentException("仅深度模式支持研究");
        }
        // 计划就绪:PLANNING 明确拒绝(异步生成中);存量行 plan_status 可能为 null 但已有计划,由下方 n==0 兜底
        if ("PLANNING".equals(b.getPlanStatus())) {
            throw new IllegalStateException("研究计划尚未就绪");
        }
        boolean hasTaskBrief = b.getTaskBrief() != null && !b.getTaskBrief().isBlank();
        boolean hasClarifyAnswers = b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank();
        if (!hasTaskBrief && !hasClarifyAnswers) {
            throw new IllegalArgumentException("意图澄清尚未完成");
        }
        JsonNode plan = json.readTree(b.getResearchPlan() == null ? "{}" : b.getResearchPlan());
        List<String> questions = new ArrayList<>();
        for (JsonNode q : plan.path("keyQuestions")) questions.add(q.asText());
        // R3(09-27-brief-writing-linkage-fix):预算内优先保背景型问题(兜底背景题 append 在尾部,
        // 旧「截前 N 条」会优先丢它,与 R2「背景题必须拿到 WEB 素材」相互挫败)
        List<Integer> window = selectResearchWindow(questions, props.getMaxAgents());
        int n = window.size();
        if (n == 0) throw new IllegalStateException("研究计划无关键问题");

        // 运行互斥:同一 brief 未结束前拒绝重复触发(避免重复付费外部调用)
        if (!runningBriefs.add(briefId)) {
            throw new IllegalStateException("该 brief 正在研究中，请勿重复触发");
        }
        boolean released = false;
        try {
            // 落全 PENDING 占位(前端轮询立即可见 agent 列表)
            List<Map<String, Object>> pending = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("agentId", i + 1);
                note.put("question", questions.get(window.get(i)));
                note.put("status", "PENDING");
                note.put("factsJson", "{\"facts\":[],\"gaps\":[]}");
                note.put("webCount", 0);
                pending.add(note);
            }
            b.setResearchNotes(json.writeValueAsString(pending));
            briefMapper.updateById(b);

            // 策略与开关快照:启动时解析一次,同批次共享(R6)
            WebSearchSnapshot snapshot = resolveSnapshot(briefId);
            // 后台异步执行(逐 agent 落库)
            self.runAsync(briefId, snapshot);
            released = true;   // runAsync 负责最终释放
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("briefId", briefId);
            out.put("agents", n);
            out.put("started", true);
            out.put("strategy", snapshot.strategyLabel());
            out.put("webProviderOrder", snapshot.strategyRaw());
            return out;
        } finally {
            if (!released) runningBriefs.remove(briefId);
        }
    }

    /**
     * 研究窗口选择器(09-27-brief-writing-linkage-fix R3):在 {@code maxAgents} 预算内
     * **优先保留背景/来龙去脉型问题**,其余按原序补足;返回按原序稳定的索引列表。
     *
     * <p>动机:研究计划 keyQuestions 可达 8 条(3~7 LLM + ≤1 兜底背景题),而 maxAgents 默认 6;
     * 旧实现「截前 N 条」会优先丢掉 append 在尾部的兜底背景题,与 R2「背景题必须拿到 WEB 素材」相互挫败。
     *
     * <p>纯函数、无副作用、不调 LLM;两处调用(run 落占位/doRunAsync 执行)共用同一索引选择,
     * 保证 question 与 toolHints 索引对齐;排序归位后 agent 顺序自然、与原计划一致。
     *
     * @return 原序升序的索引列表(长度 = min(questions.size(), maxAgents))
     */
    static List<Integer> selectResearchWindow(List<String> questions, int maxAgents) {
        List<Integer> idx = new ArrayList<>();
        if (questions == null || questions.isEmpty() || maxAgents <= 0) return idx;
        int n = Math.min(questions.size(), maxAgents);
        List<Integer> bg = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            if (ResearchPlannerService.isBackgroundQuestion(questions.get(i))) bg.add(i);
        }
        int remaining = n - bg.size();
        if (remaining <= 0) {
            // 背景题多于预算:仍按原序取前 n 条背景题
            idx.addAll(bg.subList(0, n));
        } else {
            idx.addAll(bg);
            for (int i = 0; i < questions.size() && remaining > 0; i++) {
                if (!ResearchPlannerService.isBackgroundQuestion(questions.get(i))) {
                    idx.add(i);
                    remaining--;
                }
            }
        }
        java.util.Collections.sort(idx);   // 归位原序,与 toolHints 索引一致
        return idx;
    }

    /**
     * 补检索目标(10-04-web-followup-budget C-R1):缺口分析产物,决定 Round 2 补搜什么。
     *
     * @param question 目标种子问题(用于拼 WEB query,并优先据此定位回写 Note)
     * @param claim    目标 claim(参数型事实/被拒事实的原文;用于聚类去重与可观测)
     * @param kind     {@code param} / {@code background}(继承来源问题类型)
     * @param reason   入选规则:{@code GAP} 检索类缺口 / {@code LOW_CONFIDENCE} 单源低置信参数型 /
     *                 {@code UNANSWERED} keyQuestion 彻底未被回答
     */
    record FollowupTarget(String question, String claim, String kind, String reason) {
    }

    /** 检索类缺口识别词(与 SubAgentRunner.gapOf 生成的文案对应)。 */
    private static final String[] RETRIEVAL_GAP_MARKERS = {
            "sourceId", "URL 与 sourceId", "provider 与 sourceId", "已剔除无可靠来源"};

    /**
     * 缺口识别(纯函数、零 LLM、无副作用,与 {@link #selectResearchWindow} 同构)。
     *
     * <p>输入全部来自 {@code fact_sheet}(已落库 entries/gaps/warnings)+ 研究计划 keyQuestions,
     * 无任何新增 LLM 决策——调用量/延迟/成本可预测、可单测。三类候选(满足任一即入选):
     * <ol>
     *   <li><b>a)</b> {@code gaps} 中 reason 属检索类(引用了未知/缺失 sourceId、URL/provider 不匹配);</li>
     *   <li><b>b)</b> {@code entries} 中 {@code confidence <= 0.4} 且 {@code kind == "param"}(单源参数型事实最需交叉验证);</li>
     *   <li><b>c)</b> plan 的 keyQuestion 在 Round 1 产物中既无 entry 也无 gap(彻底没被回答)。</li>
     * </ol>
     * 按 claim 关键词归属 + 背景/参数类型聚类去重(复用 {@link ClaimSimilarity#sameClaim} 与规范化问题串),
     * 最后截断到 {@code maxFollowups}。{@code maxFollowups <= 0} 或 {@code factSheet} 为空 → 空列表(不发起任何额外调用)。
     *
     * @param factSheet   已落库 fact_sheet 的 JSON 节点(entries/gaps/warnings;可空)
     * @param keyQuestions 研究计划 keyQuestions(用于规则 c;可空)
     * @param maxFollowups 目标数上限(≤0 视为关闭)
     */
    static List<FollowupTarget> selectFollowupTargets(JsonNode factSheet, List<String> keyQuestions,
                                                       int maxFollowups) {
        List<FollowupTarget> out = new ArrayList<>();
        if (maxFollowups <= 0 || factSheet == null || !factSheet.isObject()) return out;

        // 规则 a:检索类 gaps(claim 从 gap 文案「…」中还原,失败则用 gap 原文)
        for (JsonNode g : factSheet.path("gaps")) {
            String text = g.asText("");
            if (text.isBlank() || !isRetrievalGap(text)) continue;
            String claim = extractQuotedClaim(text);
            if (claim.isBlank()) claim = text;
            addTarget(out, new FollowupTarget(claim, claim, "param", "GAP"));
        }
        // 规则 b:低置信参数型 entries(confidence<=0.4 && kind==param)
        for (JsonNode e : factSheet.path("entries")) {
            double conf = e.path("confidence").asDouble(1.0);
            String kind = e.path("kind").asText("param");
            if (conf > 0.4 || !"param".equals(kind)) continue;
            String claim = e.path("claim").asText("");
            if (claim.isBlank()) claim = e.path("key").asText("");
            if (claim.isBlank()) continue;
            addTarget(out, new FollowupTarget(claim, claim, "param", "LOW_CONFIDENCE"));
        }
        // 规则 c:keyQuestion 既无 entry 也无 gap(彻底没被回答)
        if (keyQuestions != null) {
            for (String q : keyQuestions) {
                if (q == null || q.isBlank()) continue;
                if (answeredBySheet(factSheet, q)) continue;
                String kind = ResearchPlannerService.isBackgroundQuestion(q) ? "background" : "param";
                addTarget(out, new FollowupTarget(q, q, kind, "UNANSWERED"));
            }
        }
        return out.size() > maxFollowups ? new ArrayList<>(out.subList(0, maxFollowups)) : out;
    }

    /** gap 文案是否属检索类缺口(含 sourceId/URL/provider 不匹配或已被剔除)。 */
    private static boolean isRetrievalGap(String gap) {
        for (String marker : RETRIEVAL_GAP_MARKERS) if (gap.contains(marker)) return true;
        return false;
    }

    /** 从 gap 文案「claim」中还原被拒事实 claim;无「」包裹返回空串。 */
    private static String extractQuotedClaim(String gap) {
        int l = gap.indexOf('「');
        int r = gap.indexOf('」', l + 1);
        if (l >= 0 && r > l) return gap.substring(l + 1, r).trim();
        return "";
    }

    /** 聚类去重后加入(复用 ClaimSimilarity.sameClaim 或规范化问题串相同即视为同一目标)。 */
    private static void addTarget(List<FollowupTarget> out, FollowupTarget t) {
        String nq = ClaimSimilarity.normalize(t.question());
        for (FollowupTarget e : out) {
            if (!nq.isEmpty() && nq.equals(ClaimSimilarity.normalize(e.question()))) return;
            if (ClaimSimilarity.sameClaim(e.claim(), "", t.claim(), "")) return;
        }
        out.add(t);
    }

    /** keyQuestion 是否已被 fact_sheet 回答(任一 entry.claim 或 gap 文案与问题规范化后互相包含)。 */
    private static boolean answeredBySheet(JsonNode factSheet, String question) {
        String nq = ClaimSimilarity.normalize(question);
        if (nq.isEmpty()) return true;
        for (JsonNode e : factSheet.path("entries")) {
            if (contains(nq, ClaimSimilarity.normalize(e.path("claim").asText("")))) return true;
        }
        for (JsonNode g : factSheet.path("gaps")) {
            if (contains(nq, ClaimSimilarity.normalize(g.asText("")))) return true;
        }
        return false;
    }

    /** a 与 b 规范化后互相包含(短串长度 <4 视为信息不足,不判含)。 */
    private static boolean contains(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= 4 && longer.contains(shorter);
    }

    /** 解析本次研究的有效策略与开关快照:运行时设置(非空优先)> 部署级默认;开关两路相与。 */
    public WebSearchSnapshot resolveSnapshot(Long briefId) {
        String raw = settingService.getWebProviderOrder();
        WebProviderOrder order = (raw == null || raw.isBlank())
                ? WebProviderOrder.parse(props.getWebProviderOrder())
                : WebProviderOrder.parse(raw);
        boolean allowed = props.isSearchWebEnabled() && settingService.isWebSearchEnabled();
        // 10-04-web-fanout-merge B:策略/primary 组/质量门由部署级配置解析一次;运行时设置不放开(成本敏感)
        return WebSearchSnapshot.of(order, allowed, briefId, 5,
                props.effectiveSearchStrategy(), props.effectivePrimaryProviders(),
                props.effectiveWebDenyDomains(), props.effectiveWebAllowDomains());
    }

    /** 异步执行研究(逐 agent 落库;由 self 代理调用)。 */
    @Async
    public void runAsync(Long briefId, WebSearchSnapshot snapshot) {
        try {
            doRunAsync(briefId, snapshot);
        } finally {
            // 先清锁再释放运行互斥:新批次必须等 runningBriefs 放行后才能注册并 createIfAbsent 新锁,
            // 故此处顺序可保证旧批次的 remove 不会误删新批次刚创建的锁(否则两个写者各持不同锁 → 丢更新)。
            notesLocks.remove(briefId);   // 批次结束清理 per-brief 锁,避免 map 无界增长
            runningBriefs.remove(briefId);
        }
    }
    /** 异步执行主体:研究 + 汇总手册 + 自动简报;失败日志化不抛出。 */
    private void doRunAsync(Long briefId, WebSearchSnapshot snapshot) {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) return;
        // 10-04 C:批次上下文(三级预算/批次内缓存/跨轮去重);随批次释放,不跨用户/请求
        com.sparkora.deep.search.WebBatchContext batch = new com.sparkora.deep.search.WebBatchContext(
                com.sparkora.deep.search.WebCallBudget.of(props.effectiveWebCallBudgetPerRound(),
                        props.effectiveWebCallBudget(), props.effectiveWebFollowupMax(),
                        props.getMaxAgents(), effectivePrimary(snapshot)),
                new com.sparkora.deep.search.WebSearchCache(props.effectiveWebCacheTtlMs()));
        batch.startCollectPhase();
        try {
            doRunAsyncWithBatch(briefId, snapshot, batch);
        } finally {
            // 批次结束释放缓存/已见 URL(不留跨批次数据)
            try { batch.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * 异步执行主体(持有批次上下文):研究 + 汇总手册 + Round 2 补检索 + 自动简报;失败日志化不抛出。
     */
    private void doRunAsyncWithBatch(Long briefId, WebSearchSnapshot snapshot,
                                     com.sparkora.deep.search.WebBatchContext batch) {
        // 快照随批次携带 batch(父设计 §4.3:批次上下文挂快照),供子代理 WEB 搜索统一受预算/缓存/去重治理
        WebSearchSnapshot snap = snapshot.withBatch(batch);
        try {
            ArticleBriefEntity b = briefMapper.selectById(briefId);
            if (b == null) return;
            JsonNode plan = json.readTree(b.getResearchPlan() == null ? "{}" : b.getResearchPlan());
            List<String> questions = new ArrayList<>();
            List<String> toolHints = new ArrayList<>();
            for (JsonNode q : plan.path("keyQuestions")) questions.add(q.asText());
            JsonNode hints = plan.path("toolHints");
            if (hints.isTextual()) {
                try { hints = json.readTree(hints.asText()); } catch (Exception ignored) { }
            }
            // 兼容 LLM 把 toolHints 数组化为字符串数组(plan 为纯字符串,无 tools 字段):逐条按 keyQuestion 对应
            if (hints.isArray() && hints.size() > 0 && hints.get(0).isTextual()) {
                List<String> qs = new ArrayList<>();
                for (JsonNode q : plan.path("keyQuestions")) qs.add(q.asText());
                toolHints.clear();
                for (JsonNode h : hints) toolHints.add(json.writeValueAsString(List.of(qs)));
                hints = null;
            }
            for (JsonNode t : hints == null ? java.util.List.<JsonNode>of() : hints) {
                toolHints.add(t.path("tools").toString());
            }
            // R3:与研究启动阶段同一选择器(预算内保背景题),按索引取 questions/toolHints 保证对齐
            List<Integer> window = selectResearchWindow(questions, props.getMaxAgents());
            int n = window.size();
            if (n == 0) return;

            // webQuota 语义:单 provider 返回条数上限(策略路由只采信首个有效 provider)
            int webQuotaPerAgent = snapshot.webAllowed() ? Math.max(1, 8 / n) : 0;
            // R1 锚点车型解析:项目关联为准;为空时按主题识别兜底(失败不阻断研究)
            ArticleBriefEntity b0 = briefMapper.selectById(briefId);
            Long projectId = b0 == null ? null : b0.getProjectId();
            List<Long> anchors = projectId == null ? List.of() : resolveAnchors(projectId);
            String topic = projectId == null ? "" : resolveTopic(projectId);
            // R7:仅已锁定答案进入 WEB query(未锁定/无答案不注入)
            String lockedAnswers = b0 == null ? null : b0.getClarifyAnswers();
            // 10-02 R4b:内容描述解析一次,同批次全部子代理共用(仅进汇总上下文,不改检索 query)
            String contentDescription = projectId == null ? "" : resolveContentDescription(projectId);
            log.info("深度研究启动 briefId={} projectId={} strategy={} webAllowed={} anchors={} webBudget={}/{} followupMax={}",
                    briefId, projectId, snapshot.strategyLabel(), snapshot.webAllowed(), anchors,
                    batch.budget().perRoundLimit(), batch.budget().totalLimit(), props.effectiveWebFollowupMax());
            // R1/AC-01:启动阶段一次性把全部 agent 置 RUNNING(早于任何 submit,前端首轮轮询即可见
            // 多 agent 并行 RUNNING;消除「逐个到轮次才置 RUNNING → PENDING→DONE 瞬变」)
            List<Map<String, Object>> running = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("agentId", i + 1);
                note.put("question", questions.get(window.get(i)));
                note.put("status", "RUNNING");
                note.put("factsJson", "{\"facts\":[],\"gaps\":[]}");
                note.put("webCount", 0);
                running.add(note);
            }
            writeNotes(briefId, running);

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<SubAgentRunner.Note>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final int qIdx = window.get(i);
                String q = questions.get(qIdx);
                // WEB 门控用快照(AC-05:同批次同一开关快照),KB 门控仍读运行时设置
                List<String> tools = applySettingGates(
                        qIdx < toolHints.size() ? parseTools(toolHints.get(qIdx)) : List.of("KB"),
                        snapshot.webAllowed());
                futures.add(pool.submit(() -> subAgent.research(q, tools, webQuotaPerAgent, anchors, topic,
                        lockedAnswers, contentDescription, snap)));
            }
            // R2/AC-02:为每个 agent 提交**独立收集器**——谁的 future 先完成谁先回写,天然乱序,
            // 不再被慢的 future[0] 阻塞后继 agent 的落库(消除集中 PENDING→DONE 瞬变)。
            java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger webTotal = new java.util.concurrent.atomic.AtomicInteger();
            List<Future<?>> collectors = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                final int idx = i;
                final String q = questions.get(window.get(i));
                final Future<SubAgentRunner.Note> f = futures.get(i);
                collectors.add(pool.submit(() -> {
                    Map<String, Object> note = new LinkedHashMap<>();
                    note.put("agentId", idx + 1);
                    note.put("question", q);
                    note.put("factsJson", "{\"facts\":[],\"gaps\":[]}");
                    try {
                        SubAgentRunner.Note r = f.get(props.getResearchTimeoutMs(), TimeUnit.MILLISECONDS);
                        note.put("status", r.status());
                        note.put("factsJson", r.factsJson());
                        note.put("webCount", r.webCount());
                        if (r.search() != null) note.put("search", r.search());
                        doneCount.incrementAndGet();
                        webTotal.addAndGet(r.webCount());
                    } catch (Exception e) {
                        // R4/R6:超时/失败:取消该任务,避免后台继续产生外部调用;其余 agent 不受影响
                        try { f.cancel(true); } catch (Exception ignored) { }
                        note.put("status", "FAILED");
                        note.put("factsJson", "{\"facts\":[],\"gaps\":[\"子代理超时或失败\"]}");
                        note.put("webCount", 0);
                    }
                    writeAgent(briefId, idx + 1, note);
                }));
            }
            // 等待全部收集器落定(内部已带 researchTimeoutMs 兜底,故此处无超时;仅用于「全部 agent 已落库」判定)
            for (Future<?> c : collectors) {
                try { c.get(); } catch (Exception ignored) { }
            }
            pool.shutdown();
            // 汇总事实手册(基于最终 notes)
            ArticleBriefEntity latest = briefMapper.selectById(briefId);
            if (latest != null) {
                latest.setFactSheet(factSheet.merge(latest.getResearchNotes()));
                briefMapper.updateById(latest);
            }
            // 10-04 C:Round 2 覆盖驱动补检索(Round 1 汇总后;不新建子代理、不重跑 plan)
            if (latest != null && latest.getFactSheet() != null && !latest.getFactSheet().isBlank()
                    && snapshot.webAllowed() && props.effectiveWebFollowupMax() > 0) {
                try {
                    runFollowup(briefId, snap, batch, questions, topic, lockedAnswers, contentDescription,
                            anchors);
                } catch (Exception fe) {
                    // Round 2 整体失败降级不阻断:Round 1 产物照常进入简报
                    log.warn("Round 2 补检索整体降级 briefId={} error={}", briefId, fe.getClass().getSimpleName());
                }
                // 二次汇总(含 Round 2 追加的 facts/gaps)
                latest = briefMapper.selectById(briefId);
                if (latest != null) {
                    latest.setFactSheet(factSheet.merge(latest.getResearchNotes()));
                    briefMapper.updateById(latest);
                }
            }
            // 事实手册就绪 → 自动生成简报(S9 修复:确定研究计划/研究完成后必须产出简报页面,
            // 此前深度链路从不落简报字段,currentBriefId 不指向深度 brief,简报页结构性缺失)。
            // 10-04 C:仅在两轮合并之后调用一次(AC-C8)。
            if (latest != null && latest.getFactSheet() != null && !latest.getFactSheet().isBlank()) {
                try {
                    briefService.generateFromFactSheet(latest.getProjectId(), briefId);
                    log.info("深度简报已自动生成 projectId={} briefId={}", latest.getProjectId(), briefId);
                } catch (Exception be) {
                    // 自动简报失败不回滚研究产物:状态留在 DRAFT + lastBriefError,用户可在深度面板手动重试
                    log.warn("深度简报自动生成失败 projectId={} briefId={}: {}",
                            latest.getProjectId(), briefId, be.getMessage());
                }
            }
            log.info("深度研究完成 briefId={} strategy={} agents={} done={} webAccepted={} deduped={} cacheHit={} budgetExhausted={}",
                    briefId, snapshot.strategyLabel(), futures.size(), doneCount.get(), webTotal.get(),
                    batch.dedupedCount(), batch.cache().hits(), batch.budget().budgetExhausted());
        } catch (Exception e) {
            log.error("深度研究异步执行失败 briefId={}: {}", briefId, e.getMessage(), e);
        }
    }

    /**
     * Round 2 覆盖驱动补检索(10-04-web-followup-budget C-R2)。
     *
     * <p>数据流:gap 分析(纯函数 {@link #selectFollowupTargets})→ 每目标 1 次 PRIMARY_FANOUT 多源搜索
     * + ≤1 次 LLM 增量抽取 → 经 {@link #updateAgent} 增量写回(保留既有 {@code search} 字段)。
     * <b>不新建子代理、不重跑 plan</b>;超时/异常/空结果记 warning 并跳过该目标,降级不阻断。
     */
    private void runFollowup(Long briefId, WebSearchSnapshot snapshot, com.sparkora.deep.search.WebBatchContext batch,
                              List<String> questions, String topic, String lockedAnswers, String contentDescription,
                              List<Long> anchors) throws Exception {
        ArticleBriefEntity cur = briefMapper.selectById(briefId);
        if (cur == null) return;
        JsonNode factSheetNode = json.readTree(cur.getFactSheet());
        int maxFollowups = props.effectiveWebFollowupMax();
        List<FollowupTarget> targets = selectFollowupTargets(factSheetNode, questions, maxFollowups);
        if (targets.isEmpty() || budgetGuardExhausted(batch)) {
            log.info("Round 2 无补检索目标或预算已耗尽,跳过 briefId={} targets=0", briefId);
            return;
        }
        batch.startFollowupPhase();
        batch.budget().enterFollowupPhase();
        log.info("Round 2 补检索启动 briefId={} targets={}", briefId, targets.size());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> tasks = new ArrayList<>();
        for (FollowupTarget t : targets) {
            if (!batch.budget().tryAcquireFollowupSlot()) break;
            tasks.add(pool.submit(() -> {
                try {
                    runFollowupTarget(briefId, snapshot, batch, t, topic, lockedAnswers, contentDescription,
                            anchors);
                } catch (Exception e) {
                    // 单个目标失败降级跳过:已获证据照常入册,不阻断研究
                    log.warn("Round 2 目标补检索降级 briefId={} reason={} error={}",
                            briefId, t.reason(), e.getClass().getSimpleName());
                }
            }));
        }
        for (Future<?> f : tasks) {
            try {
                f.get(props.effectiveFollowupTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 超时/异常:取消该任务并跳过该目标(降级不阻断);已写回的其它目标不受影响
                try { f.cancel(true); } catch (Exception ignored) { }
                log.warn("Round 2 目标超时或失败,跳过 briefId={} error={}", briefId, e.getClass().getSimpleName());
            }
        }
        pool.shutdown();
    }

    /** 对单个目标执行 1 次多源搜索 + ≤1 次 LLM 抽取并增量写回对应 Note。 */
    private void runFollowupTarget(Long briefId, WebSearchSnapshot snapshot,
                                    com.sparkora.deep.search.WebBatchContext batch, FollowupTarget target,
                                    String topic, String lockedAnswers, String contentDescription,
                                    List<Long> anchors) {
        // query 确定性拼装(复用 webQuery,把「问题」替换成目标 claim;继承 isNegativeAnswer 防护),默认走 web 垂直
        String query = SubAgentRunner.webQuery(topic, target.claim(), lockedAnswers);
        SubAgentRunner.Note note = subAgent.researchFollowup(target.claim(), query, anchors, topic,
                contentDescription, snapshot, batch);
        if (note == null) return;
        writeFollowupNote(briefId, target, note);
    }

    /**
     * 增量写回补检索结果(AC-C2:不新建子代理——只并入 Round 1 产出的对应 Note)。
     *
     * <p>定位「对应 Note」的确定性规则(与 {@code selectFollowupTargets} 的输入同源):
     * 目标的 claim 必来自某条 Round 1 Note 的 factsJson(facts[].claim 或 gaps[])→ 按此匹配;
     * 匹配不到再按问题规范化匹配;仍无则并入首条 Note。任一情况都<b>不新增 agent 条目</b>。
     *
     * <p>复用 {@link #updateAgent} 的读改写机制(显式保留既有 {@code search} 字段防丢更新);
     * factsJson 以 Round 1 结果为底、追加 Round 2 新 facts/gaps,避免覆盖。
     */
    private void writeFollowupNote(Long briefId, FollowupTarget target, SubAgentRunner.Note note) {
        Object lock = notesLocks.computeIfAbsent(briefId, k -> new Object());
        synchronized (lock) {
            try {
                ArticleBriefEntity b = briefMapper.selectById(briefId);
                if (b == null || b.getResearchNotes() == null) return;
                JsonNode arr = json.readTree(b.getResearchNotes());
                List<JsonNode> nodes = new ArrayList<>();
                for (JsonNode n : arr) nodes.add(n);
                if (nodes.isEmpty()) return;
                int targetIdx = locateNote(nodes, target);
                List<Map<String, Object>> out = new ArrayList<>();
                for (int i = 0; i < nodes.size(); i++) {
                    JsonNode n = nodes.get(i);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("agentId", n.path("agentId").asInt());
                    m.put("question", n.path("question").asText());
                    m.put("status", n.path("status").asText());
                    m.put("factsJson", n.path("factsJson").asText());
                    m.put("webCount", n.path("webCount").asInt());
                    Map<String, Object> existingSearch = n.has("search") && !n.path("search").isNull()
                            ? json.convertValue(n.path("search"), Map.class) : null;
                    if (existingSearch != null) m.put("search", existingSearch);
                    if (i == targetIdx) {
                        m.put("factsJson", appendFacts(n.path("factsJson").asText(), note.factsJson()));
                        m.put("webCount", n.path("webCount").asInt() + note.webCount());
                        Map<String, Object> mergedSearch = mergeSearch(existingSearch, note.search());
                        if (mergedSearch != null) m.put("search", mergedSearch);
                    }
                    out.add(m);
                }
                b.setResearchNotes(json.writeValueAsString(out));
                briefMapper.updateById(b);
            } catch (Exception e) {
                log.warn("Round 2 增量写回失败 briefId={} error={}", briefId, e.getClass().getSimpleName());
            }
        }
    }

    /** 定位目标对应的 Round 1 Note 下标:按 claim 出现于 facts/gaps → 按问题规范化 → 首条。 */
    private static int locateNote(List<JsonNode> nodes, FollowupTarget target) {
        String claim = ClaimSimilarity.normalize(target.claim());
        if (!claim.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                if (factsJsonContains(nodes.get(i).path("factsJson").asText(""), claim)) return i;
            }
        }
        String q = ClaimSimilarity.normalize(target.question());
        if (!q.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                String nq = ClaimSimilarity.normalize(nodes.get(i).path("question").asText(""));
                if (!nq.isEmpty() && (nq.contains(q) || q.contains(nq))) return i;
            }
        }
        return 0;
    }

    /** factsJson(facts[].claim + gaps[]) 是否含规范化 claim 子串。 */
    private static boolean factsJsonContains(String factsJson, String normalizedClaim) {
        if (factsJson == null || factsJson.isBlank() || normalizedClaim.length() < 4) return false;
        try {
            JsonNode root = PLAIN_JSON.readTree(factsJson);
            for (JsonNode f : root.path("facts")) {
                if (ClaimSimilarity.normalize(f.path("claim").asText("")).contains(normalizedClaim)) return true;
            }
            for (JsonNode g : root.path("gaps")) {
                if (ClaimSimilarity.normalize(g.asText("")).contains(normalizedClaim)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 静态解析用 ObjectMapper(仅解析 notes factsJson,与注入实例用途隔离)。 */
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper();

    /** 合并两段搜索元数据:保留 Round 1 的 attempts 并追加 Round 2 attempts,C 观测字段取新值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeSearch(Map<String, Object> base, SubAgentRunner.SearchMeta add) {
        if (add == null) return base;
        Map<String, Object> out = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        List<Object> attempts = new ArrayList<>();
        Object baseAttempts = out.get("attempts");
        if (baseAttempts instanceof List<?> list) attempts.addAll(list);
        if (add.attempts() != null) attempts.addAll((List<Object>) (List<?>) add.attempts());
        out.put("attempts", attempts);
        out.put("providers", add.providers());
        out.put("dedupedCount", add.dedupedCount());
        out.put("cacheHit", add.cacheHit());
        out.put("budgetExhausted", add.budgetExhausted());
        return out;
    }

    /** 合并两段 factsJson:facts/gaps 均追加去重(Round 2 新 facts 追加到 Round 1 之后)。 */
    private String appendFacts(String baseJson, String addJson) throws Exception {
        JsonNode base = json.readTree(baseJson == null || baseJson.isBlank() ? "{\"facts\":[],\"gaps\":[]}" : baseJson);
        JsonNode add = json.readTree(addJson == null || addJson.isBlank() ? "{\"facts\":[],\"gaps\":[]}" : addJson);
        List<JsonNode> facts = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (JsonNode f : base.path("facts")) facts.add(f);
        for (JsonNode f : add.path("facts")) facts.add(f);   // 跨轮 URL 去重已在搜索层完成,此处按出现序追加
        for (JsonNode g : base.path("gaps")) {
            String gs = g.asText("");
            if (!gs.isBlank() && !gaps.contains(gs)) gaps.add(gs);
        }
        for (JsonNode g : add.path("gaps")) {
            String gs = g.asText("");
            if (!gs.isBlank() && !gaps.contains(gs)) gaps.add(gs);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("facts", facts);
        merged.put("gaps", gaps);
        return json.writeValueAsString(merged);
    }

    /** 生效 primary 组(快照 primary 与 order 的交集;供预算保护性下限只算计量源)。 */
    private static List<com.sparkora.deep.search.WebProvider> effectivePrimary(WebSearchSnapshot snapshot) {
        List<com.sparkora.deep.search.WebProvider> out = new ArrayList<>();
        for (com.sparkora.deep.search.WebProvider p : snapshot.providers()) {
            if (snapshot.primaryProviders().contains(p)) out.add(p);
        }
        return out;
    }

    /** 预算是否已耗尽(供 Round 2 前置短路)。 */
    private static boolean budgetGuardExhausted(com.sparkora.deep.search.WebBatchContext batch) {
        return batch == null || batch.budget().budgetExhausted();
    }

    /**
     * 整体覆写 research_notes(启动阶段一次性置 RUNNING 用)。
     * 与 {@link #updateAgent} 共用同一 per-brief 锁,避免与并发收集器回写交错。
     */
    private void writeNotes(Long briefId, List<Map<String, Object>> notes) {
        Object lock = notesLocks.computeIfAbsent(briefId, k -> new Object());
        synchronized (lock) {
            try {
                ArticleBriefEntity b = briefMapper.selectById(briefId);
                if (b == null) return;
                b.setResearchNotes(json.writeValueAsString(notes));
                briefMapper.updateById(b);
            } catch (Exception e) {
                log.warn("初始化 agent 状态失败 briefId={}: {}", briefId, e.getMessage());
            }
        }
    }

    /**
     * 回写单个 agent 结果(收集器完成即调用)。抽出便于复用并统一走 per-brief 锁。
     */
    private void writeAgent(Long briefId, int agentId, Map<String, Object> note) {
        updateAgent(briefId, agentId, note);
    }

    /**
     * 更新 research_notes 中指定 agentId 的条目(读改写,幂等)。
     *
     * <p>09-26 并发安全:多个收集器线程可能同时回写同一 brief,「读整段 JSON → 改一个 agentId → 写回」
     * 若无锁会丢失更新(丢 status/factsJson/webCount/search)。以 per-brief 锁串行化:
     * 同一 brief 的写入互斥,不同 brief 并行不受影响(同批次已由 runningBriefs 保证单批)。
     */
    private void updateAgent(Long briefId, int agentId, Map<String, Object> note) {
        Object lock = notesLocks.computeIfAbsent(briefId, k -> new Object());
        synchronized (lock) {
            try {
                ArticleBriefEntity b = briefMapper.selectById(briefId);
                if (b == null || b.getResearchNotes() == null) return;
                JsonNode arr = json.readTree(b.getResearchNotes());
                List<Map<String, Object>> notes = new ArrayList<>();
                for (JsonNode n : arr) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("agentId", n.path("agentId").asInt());
                    m.put("question", n.path("question").asText());
                    m.put("status", n.path("status").asText());
                    m.put("factsJson", n.path("factsJson").asText());
                    m.put("webCount", n.path("webCount").asInt());
                    // 保留既有 search 元数据(读改写不丢字段:R10 可观测)
                    if (n.has("search") && !n.path("search").isNull()) m.put("search", json.convertValue(n.path("search"), Map.class));
                    if (n.path("agentId").asInt() == agentId) {
                        m.put("status", note.get("status"));
                        m.put("factsJson", note.get("factsJson"));
                        m.put("webCount", note.get("webCount"));
                        if (note.containsKey("search")) m.put("search", note.get("search"));
                    }
                    notes.add(m);
                }
                b.setResearchNotes(json.writeValueAsString(notes));
                briefMapper.updateById(b);
            } catch (Exception e) {
                log.warn("更新 agent 状态失败 briefId={} agentId={}: {}", briefId, agentId, e.getMessage());
            }
        }
    }

    private List<String> parseTools(String toolsJson) {
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode t : json.readTree(toolsJson)) out.add(t.asText());
            return out.isEmpty() ? List.of("KB") : out;
        } catch (Exception e) {
            return List.of("KB");
        }
    }

    /**
     * 设置开关门控(09-09-brief-gen-redesign R3):运行时设置优先于研究计划的工具提示。
     * - kbEnabled=false → 剔除 KB(外部搜索优先,知识库停用期间不装配 KB 工具);
     * - webSearchEnabled=false → 剔除 WEB;
     * - 双关(全空) → 返回空列表,SubAgentRunner 无资料工具,prompt 由研究阶段注入「未检索任何外部资料」要求。
     *
     * @param webAllowed 快照开关(AC-05:同批次共用同一开关快照,启动后改设置不影响);KB 仍读运行时设置
     */
    private List<String> applySettingGates(List<String> tools, boolean webAllowed) {
        List<String> out = new ArrayList<>(tools);
        if (!settingService.isKbEnabled()) out.remove("KB");
        if (!webAllowed) out.remove("WEB");
        return out;
    }

    /** R1 锚点车型解析:项目关联车型为准;为空时主题识别兜底(与 create 建项同一 matcher,失败不阻断研究)。 */
    private List<Long> resolveAnchors(Long projectId) {
        try {
            List<Long> ids = carService.listModelIds(projectId);
            if (ids != null && !ids.isEmpty()) return ids;
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            if (p == null) return List.of();
            com.sparkora.car.service.CarModelMatcherService.MatchResult m = matcherService.match(p.getTopic(), p.getContentDescription());
            return m == null || !m.related() ? List.of() : m.modelIds();
        } catch (Exception e) {
            log.warn("锚点车型解析失败,退化无锚点 projectId={}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    /** R1 项目主题(复合 KB 检索语料;空则回退空串)。 */
    private String resolveTopic(Long projectId) {
        try {
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            return p == null || p.getTopic() == null ? "" : p.getTopic();
        } catch (Exception e) {
            return "";
        }
    }

    /** 10-02 R4b 项目内容描述(仅进子代理 LLM 汇总上下文;空则回退空串,不改检索 query)。 */
    private String resolveContentDescription(Long projectId) {
        try {
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            return p == null || p.getContentDescription() == null ? "" : p.getContentDescription();
        } catch (Exception e) {
            return "";
        }
    }
}