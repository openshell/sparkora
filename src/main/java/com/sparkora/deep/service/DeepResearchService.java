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
            log.info("深度研究启动 briefId={} projectId={} strategy={} webAllowed={} anchors={}",
                    briefId, projectId, snapshot.strategyLabel(), snapshot.webAllowed(), anchors);
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
                        lockedAnswers, contentDescription, snapshot)));
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
            // 事实手册就绪 → 自动生成简报(S9 修复:确定研究计划/研究完成后必须产出简报页面,
            // 此前深度链路从不落简报字段,currentBriefId 不指向深度 brief,简报页结构性缺失)
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
            log.info("深度研究完成 briefId={} strategy={} agents={} done={} webAccepted={}",
                    briefId, snapshot.strategyLabel(), futures.size(), doneCount.get(), webTotal.get());
        } catch (Exception e) {
            log.error("深度研究异步执行失败 briefId={}: {}", briefId, e.getMessage(), e);
        }
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