package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 澄清阶段(S9 ①②):主代理解析主题 → 研究计划 + 一次性结构化澄清问题。
 * 产物全部落 brief(research_plan/clarify_questions);用户提交答案锁定 clarify_answers。
 * 只生成不调外部工具;LLM 调用 1 次(计划与问题一并产出)。
 *
 * 09-11 异步化(对齐 DeepResearchService.run/runAsync 范式):
 *  - start() 同步毫秒级:清理陈旧 PLANNING → 落 PLANNING 占位行 → self.runAsync 后台生成 → 返回;
 *  - runAsync() @Async 调 LLM,成功回写 plan/questions + plan_status=READY,失败删占位行 + 写 lastBriefError。
 * 部分唯一索引 uq_brief_planning 保证同一项目同时至多一条 PLANNING,并发触发撞索引转 409。
 */
@Slf4j
@Service
public class ClarifyService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleProjectMapper projectMapper;
    /** 车型知识库名录(S9 修复):反问问题必须基于真实车库车型,而非模型凭主题猜测。 */
    private final com.sparkora.car.service.CarModelService carModelService;
    /** 项目状态机唯一写权持有者(异步链路 last_brief_error 写入/清空,09-27-state-machine-service)。 */
    private final com.sparkora.service.ProjectStatusService statusService;
    /** 陈旧占位清理阈值(与状态服务 STALE_GENERATING_MS 同款口径,brief 侧 plan_status 自愈)。 */
    private static final long STALE_PLANNING_MS = com.sparkora.service.ProjectStatusService.STALE_GENERATING_MS;
    // 自注入代理,确保 @Async 生效(start 内 this.runAsync 不会走代理)
    @Autowired
    @Lazy
    private ClarifyService self;

    public ClarifyService(AiClient aiClient, ObjectMapper json,
                          ArticleBriefMapper briefMapper, ArticleProjectMapper projectMapper,
                          com.sparkora.car.service.CarModelService carModelService,
                          com.sparkora.service.ProjectStatusService statusService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.projectMapper = projectMapper;
        this.carModelService = carModelService;
        this.statusService = statusService;
    }

    /**
     * 启动研究计划生成(同步毫秒级,202 语义):落 PLANNING 占位行后立即返回,后台 self.runAsync 生成。
     *
     * <p>10-02-brief-reasoning-maxtokens:签名收敛为仅 projectId——主题/内容描述/目标读者/目标字数
     * 一律从项目实体读取(不再由请求体传入,消除「body 与库不一致」窗口)。
     * @return 占位 brief(id 供前端轮询 /deep/status 引用)
     */
    public ArticleBriefEntity start(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (p.getTopic() == null || p.getTopic().isBlank()) throw new IllegalArgumentException("缺少主题");

        // 清理陈旧占位:进程中途死亡遗留的 PLANNING 行(超过阈值)删除,放行重新触发以自愈
        briefMapper.delete(new QueryWrapper<ArticleBriefEntity>()
                .eq("project_id", projectId)
                .eq("plan_status", "PLANNING")
                .lt("created_at", LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_PLANNING_MS))));

        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setProjectId(projectId);
        b.setGenMode("DEEP");
        b.setPlanStatus("PLANNING");
        b.setCreatedAt(LocalDateTime.now());
        try {
            briefMapper.insert(b);
        } catch (DuplicateKeyException e) {
            // 并发/双开触发撞部分唯一索引 uq_brief_planning:不产生重复行,转 409 语义
            throw new IllegalStateException("该项目正在生成研究计划，请稍候", e);
        }

        // 后台异步生成(经自注入代理确保 @Async 生效)
        self.runAsync(b.getId(), projectId);
        return b;
    }

    /**
     * 异步生成研究计划与澄清问题(由 self 代理调用)。
     * 成功回写 research_plan/clarify_questions/research_reasoning/ai_model/token_usage + plan_status=READY;
     * 失败删除占位行(保持「失败无残留」)并写 project.last_brief_error,状态保持 DRAFT。
     *
     * <p>10-02:异步体重取项目实体(不复用同步阶段快照),主题/内容描述/读者/字数从库读。
     */
    @Async
    public void runAsync(Long briefId, Long projectId) {
        try {
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            if (p == null) throw new IllegalArgumentException("项目不存在");
            PlanResult r = generatePlan(p);
            ArticleBriefEntity b = briefMapper.selectById(briefId);
            if (b == null) return;   // 占位行已被并发清理(如失败重试),丢弃结果
            b.setResearchPlan(r.researchPlan());
            b.setClarifyQuestions(r.questions());
            b.setResearchReasoning(r.reasoning());   // 10-02:思考过程落库(与计划同一次 update)
            b.setAiModel(r.model());
            b.setTokenUsage(r.totalTokens());
            b.setPlanStatus("READY");
            briefMapper.updateById(b);
            // 成功后清空 last_brief_error(与 BriefService 一致:失败原因成功后清空,避免重试成功后仍显示旧错误)。
            // 单列写入已收敛到 ProjectStatusService(清空分支带 isNotNull 优化,避免无谓刷新 updated_at)。
            try {
                statusService.writeBriefError(b.getProjectId(), null);
            } catch (Exception pe) {
                log.warn("清空 lastBriefError 失败 briefId={}: {}", briefId, pe.getMessage());
            }
            log.info("研究计划生成完成 briefId={}", briefId);
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            log.warn("研究计划异步生成失败 briefId={}: {}", briefId, reason);
            // 删除 PLANNING 占位行(失败无残留,/deep/status 自然回 NONE)
            try {
                ArticleBriefEntity b = briefMapper.selectById(briefId);
                if (b != null && "PLANNING".equals(b.getPlanStatus())) briefMapper.deleteById(briefId);
            } catch (Exception de) {
                log.warn("清理研究计划占位行失败 briefId={}: {}", briefId, de.getMessage());
            }
            // 失败原因落项目(单列显式 set,委托状态服务;projectId 为入参始终可用,
            // 项目被并发删除时条件 update 影响 0 行,天然幂等)
            try {
                statusService.writeBriefError(projectId, reason);
            } catch (Exception pe) {
                log.warn("写入 lastBriefError 失败 briefId={}: {}", briefId, pe.getMessage());
            }
        }
    }

    /** LLM 生成主体:返回研究计划/澄清问题/思考过程(不落库),供异步 runAsync 调用。 */
    private PlanResult generatePlan(ArticleProjectEntity p) throws Exception {
        String topic = p.getTopic();
        String contentDescription = p.getContentDescription();
        // 车库实际车型名录注入:让反问的车型/竞品选项来自真实车库(修复「大唐主题问不到大唐EV」)
        String catalog;
        try {
            catalog = carModelService.list().stream()
                    .map(m -> m.getName() + (m.getPriceRange() == null || m.getPriceRange().isBlank()
                            ? "" : "（" + m.getPriceRange() + "）"))
                    .collect(java.util.stream.Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("车库名录获取失败,反问退化为不注车型名录: {}", e.getMessage());
            catalog = "";
        }
        // C1:固定指令外置模板 prompts/clarify/plan-system.st;动态车库名录作为 {{catalog}} 变量传入
        String system = com.sparkora.ai.PromptTemplateLoader.render("clarify/plan-system.st",
                java.util.Map.of("catalog",
                        catalog.isBlank() ? "(车库暂无车型数据,允许自由提问,但不得编造具体车型名)" : catalog));
        // 10-02 R4:澄清 prompt 注入创建输入(主题 + 内容描述 + 目标读者 + 目标字数,非空才加)
        StringBuilder user = new StringBuilder("主题:").append(topic).append('\n');
        if (contentDescription != null && !contentDescription.isBlank()) {
            user.append("内容描述:").append(contentDescription).append('\n');
        }
        if (p.getAudience() != null && !p.getAudience().isBlank()) {
            user.append("目标读者:").append(p.getAudience()).append('\n');
        }
        // 目标字数缺省回退 1500(口径对齐 BriefService/DeepWriterService/VersionService)
        user.append("目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        // 10-02 R1:reasoning 模型推理 token 也计入 max_tokens,4096 会被推理吃光导致 content 为空/截断。
        // 首次 8192;任何失败(截断 finish_reason=length / 空内容 / 非法 JSON)提额 16384 重试一次,
        // 仅两次均失败才抛(范式抄 BriefService.generateFromFactSheet)。
        AiClient.ChatResult cr;
        JsonNode node;
        try {
            cr = aiClient.chatJson(system, user.toString(), 8192);
            node = json.readTree(AiClient.sanitizeAiJson(cr.content()));
        } catch (Exception first) {
            log.warn("研究计划首次生成失败,提额重试(16384): {}", first.getMessage());
            cr = aiClient.chatJson(system,
                    user + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象,确保字段齐全。",
                    16384);
            node = json.readTree(AiClient.sanitizeAiJson(cr.content()));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("keyQuestions", toArray(node.path("keyQuestions")));
        plan.put("dataNeeds", toArray(node.path("dataNeeds")));
        plan.put("hypotheses", toArray(node.path("hypotheses")));
        plan.put("toolHints", node.path("toolHints"));
        // R2(09-26)确定性兜底:信号词命中且无背景型问题时自动补一条(LLM 判断为主,此处只兜底)
        ensureBackgroundQuestion(plan, topic, contentDescription);
        String questions = node.path("questions").toString();
        String normalized = normalizeQuestions(questions);
        return new PlanResult(json.writeValueAsString(plan), normalized, cr.model(), cr.totalTokens(), cr.reasoning());
    }

    /** generatePlan 产物(计划 JSON / 归一化问题 JSON / 模型 / token / 思考过程)。 */
    private record PlanResult(String researchPlan, String questions, String model, int totalTokens, String reasoning) {}

    /** R2 背景/来龙去脉型主题信号词:命中则主题应含至少一条背景型问题。 */
    private static final String[] BACKGROUND_SIGNALS = {
            "发布", "宣布", "建成", "落成", "完成", "启用", "战略", "计划", "规划", "政策",
            "里程碑", "首个", "突破", "布局", "进军"};

    /** R2 背景型问题检测词:现有 keyQuestions 命中任一即视为已覆盖背景维度(不重复补题)。 */
    private static final String[] BACKGROUND_TERMS = {
            "背景", "战略", "规划", "目标", "意义", "来龙去脉", "发展历程", "布局", "为什么", "如何演变"};

    /**
     * 背景/来龙去脉型问题判定(09-27-brief-writing-linkage-fix R2)。
     *
     * <p>命中 {@link #BACKGROUND_TERMS} ∪ {@link #BACKGROUND_SIGNALS} 任一即为背景型。
     * 取并集原因:LLM 生成的背景题含「背景/战略/目标」等 TERMS 词,而 R2 兜底背景题文案同样含这些词;
     * 背景「主题信号词」(发布/战略/规划…)则可能出现在问题文本里。两类均属 R2 既有信号体系,不新增词汇。
     *
     * <p>用途:① 研究子代理判定「参数型问题」才允许因 KB 命中车型域权威块跳过 WEB;
     * ② 研究窗口(受 maxAgents 截断)优先保留背景题。纯字符串判定,无副作用,包级可见供同包复用。
     */
    static boolean isBackgroundQuestion(String question) {
        if (question == null || question.isBlank()) return false;
        return java.util.Arrays.stream(BACKGROUND_TERMS).anyMatch(question::contains)
                || java.util.Arrays.stream(BACKGROUND_SIGNALS).anyMatch(question::contains);
    }

    /**
     * R2(09-26)确定性兜底:主题/内容描述命中背景信号词、且现有 keyQuestions 无背景型问题时,
     * 追加一条背景/来龙去脉型问题,并同步追加对应 toolHints(保证问题与提示 1:1,避免落到 KB-only 默认)。
     *
     * <p>LLM 判断为主(prompt 已授权按主题取舍),此处仅在模型漏掉时兜底;幂等——已含背景题或未命中信号词则原样返回。
     * 结构异常(非数组等)不抛异常,静默降级为不补。
     */
    static void ensureBackgroundQuestion(Map<String, Object> plan, String topic, String contentDescription) {
        if (plan == null) return;
        String probe = (topic == null ? "" : topic) + " " + (contentDescription == null ? "" : contentDescription);
        boolean signal = java.util.Arrays.stream(BACKGROUND_SIGNALS).anyMatch(probe::contains);
        if (!signal) return;
        Object kq = plan.get("keyQuestions");
        if (!(kq instanceof List<?> list)) return;
        for (Object q : list) {
            if (q instanceof String s && java.util.Arrays.stream(BACKGROUND_TERMS).anyMatch(s::contains)) {
                return;   // 已有背景型问题,幂等不补
            }
        }
        String t = topic == null || topic.isBlank() ? "" : topic.trim();
        String question = t.isEmpty()
                ? "该主题的行业背景、企业战略与长期目标是什么?"
                : t + " 的行业背景、企业战略与长期目标是什么?";
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) list;
        questions.add(question);
        // toolHints 仅在为数组时同步追加;非数组时不抛异常(降级为仅补问题)
        Object hints = plan.get("toolHints");
        if (hints instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            com.fasterxml.jackson.databind.node.JsonNodeFactory nf =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
            com.fasterxml.jackson.databind.node.ObjectNode hint = nf.objectNode();
            hint.put("question", question);
            com.fasterxml.jackson.databind.node.ArrayNode tools = hint.putArray("tools");
            tools.add("KB");
            tools.add("WEB");
            arr.add(hint);
        }
    }

    /** 竞品信号词:命中即视为对比竞品类问题(确定性归一化,不依赖 LLM 遵守 prompt)。 */
    private static final String[] COMPETITOR_TERMS = {"对比", "竞品", "比较", "竞对", "竞争"};

    /** 兜底选项名(归一化时缺省自动补)。 */
    private static final String NO_COMPARE_OPTION = "不对比";

    /**
     * 澄清问题归一化(R1 兜底):
     * - 问题文本含竞品信号词的选项题(type=single|multi)强制 type=multi(LLM 误给 single 的纠正);
     * - 其 options 缺「不对比」时自动补上(用户可显式放弃对比);
     * - 非选项题(input)/不含竞品词的题不动;解析失败原样返回,不阻断澄清流程。
     */
    String normalizeQuestions(String questionsJson) {
        try {
            JsonNode arr = json.readTree(questionsJson == null ? "[]" : questionsJson);
            if (!arr.isArray() || arr.isEmpty()) return questionsJson;
            List<Map<Object, Object>> out = new ArrayList<>();
            for (JsonNode n : arr) {
                Map<Object, Object> q = new LinkedHashMap<>();
                q.put("q", n.path("q").asText());
                String type = n.path("type").asText("input");
                List<String> options = new ArrayList<>();
                for (JsonNode o : n.path("options")) options.add(o.asText());
                if (options.isEmpty()) type = "input";
                String text = n.path("q").asText();
                boolean competitor = java.util.Arrays.stream(COMPETITOR_TERMS).anyMatch(text::contains);
                if (competitor && !"input".equals(type)) {
                    type = "multi";
                    if (options.stream().noneMatch(o -> o.contains(NO_COMPARE_OPTION))) options.add(NO_COMPARE_OPTION);
                }
                q.put("type", type);
                if (!"input".equals(type)) q.put("options", options);
                q.put("required", n.path("required").asBoolean(true));
                out.add(q);
            }
            return json.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("澄清问题归一化失败,原样保留: {}", e.getMessage());
            return questionsJson;
        }
    }

    /** 锁定用户答案(clarify_answers 落库;空答案项剔除)。 */
    public String lockAnswers(String questionsJson, String answersRaw) {
        // answersRaw: {answers: {"问题文本": "用户答案"}} 前端按问题文本作 key
        try {
            JsonNode a = json.readTree(answersRaw == null ? "{}" : answersRaw);
            if (a.has("answers") && a.path("answers").isObject()) a = a.path("answers");   // 兼容 {answers:{}} 包裹
            JsonNode qs = json.readTree(questionsJson == null ? "[]" : questionsJson);
            List<Map<String, Object>> locked = new ArrayList<>();
            for (JsonNode q : qs) {
                String qText = q.path("q").asText();
                String ans = a.path(qText).asText("");
                if (qText.isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("q", qText);
                item.put("a", ans.trim());
                locked.add(item);
            }
            return json.writeValueAsString(locked);
        } catch (Exception e) {
            throw new IllegalArgumentException("澄清答案解析失败: " + e.getMessage(), e);
        }
    }

    private List<String> toArray(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) for (JsonNode n : arr) out.add(n.asText());
        return out;
    }
}