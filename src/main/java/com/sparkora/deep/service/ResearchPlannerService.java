package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.PromptTemplateLoader;
import com.sparkora.ai.ResearchPlanDto;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C2 研究规划服务（10-03-gen-cognitive-redesign，design §2/§5）:把纯事实研究规划从「一次性混装澄清」
 * 中剥离为独立服务，入参为 C1 产出的结构化意图契约 {@code task_brief}。
 *
 * <p>{@link #plan} 基于 TaskBrief + 项目信息调用 LLM 产出**纯事实**研究子问题（{@code keyQuestions} 仅
 * 事实/世界维度，不含任何意图/澄清问题），组装为既有 {@code research_plan} JSON 结构
 * {@code {keyQuestions[],dataNeeds[],hypotheses[],toolHints[]}}，落到 brief.research_plan，驱动既有并行研究链路。
 *
 * <p>本服务**不写项目 status**（状态机写权归 {@code ProjectStatusService}）；仅按显式列写 brief 产物
 * （{@code UpdateWrapper} 单列 set，避免 {@code updateById} 全量回写覆盖并发列）。幂等：可重复调用覆盖 research_plan。
 */
@Slf4j
@Service
public class ResearchPlannerService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleProjectMapper projectMapper;
    /** 车型知识库名录:车型/竞品/参数类研究问题须锚定真实车库,不得编造。 */
    private final com.sparkora.car.service.CarModelService carModelService;

    public ResearchPlannerService(AiClient aiClient, ObjectMapper json,
                                   ArticleBriefMapper briefMapper, ArticleProjectMapper projectMapper,
                                   com.sparkora.car.service.CarModelService carModelService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.projectMapper = projectMapper;
        this.carModelService = carModelService;
    }

    /**
     * 基于 TaskBrief 生成纯事实研究计划并落 brief.research_plan（同步；失败提额重试一次后抛出）。
     *
     * <p>前置:brief 属于 project、{@code task_brief} 非空（意图澄清已完成），否则明确 4xx/409 语义。
     * 幂等:可重复调用，覆盖已有 research_plan。
     *
     * @return 已回写 research_plan 的 brief（字段已更新，供控制器直接返回）
     */
    public ArticleBriefEntity plan(Long projectId, Long briefId) throws Exception {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        if (b.getTaskBrief() == null || b.getTaskBrief().isBlank()) {
            throw new IllegalStateException("意图澄清尚未完成");
        }

        String topic = p.getTopic() == null ? "" : p.getTopic();
        String contentDescription = p.getContentDescription();
        // 固定指令外置模板 prompts/research/plan-system.st;{{schema}} 由 DTO 类型派生(单一来源),{{catalog}} 注入真实车库名录。
        String catalog = catalogOrEmpty();
        String system = PromptTemplateLoader.render("research/plan-system.st",
                Map.of("catalog",
                        catalog.isBlank() ? "(车库暂无车型数据,允许自由提问,但不得编造具体车型名)" : catalog,
                        "schema", AiClient.jsonSchema(ResearchPlanDto.class)));
        String user = buildUserPrompt(p, topic, contentDescription, b.getTaskBrief());

        // reasoning 模型推理 token 计入 max_tokens:首次 8192;任何失败(截断 finish_reason=length / 空内容 /
        // 反序列化失败)提额 16384 重试一次,仅两次均失败才抛(范式抄 BriefService/ClarifyConversationService)。
        AiClient.ChatResult cr;
        ResearchPlanDto dto;
        try {
            AiClient.TypedResult<ResearchPlanDto> tr = aiClient.structured(system, user, 8192, ResearchPlanDto.class);
            dto = tr.entity();
            cr = tr.chat();
        } catch (Exception first) {
            log.warn("研究计划首次生成失败,提额重试(16384): {}", first.getMessage());
            AiClient.TypedResult<ResearchPlanDto> tr = aiClient.structured(system,
                    user + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象,确保字段齐全。",
                    16384, ResearchPlanDto.class);
            dto = tr.entity();
            cr = tr.chat();
        }

        // DTO → 既有 research_plan JSON 结构(下游 DeepResearchService 消费;字段语义不变)。
        Map<String, Object> plan = new LinkedHashMap<>();
        // 必须可变:ensureBackgroundQuestion 会向 keyQuestions 追加兜底题(List.of() 不可变会抛)
        plan.put("keyQuestions", dto.getKeyQuestions() == null ? new ArrayList<>() : new ArrayList<>(dto.getKeyQuestions()));
        plan.put("dataNeeds", dto.getDataNeeds() == null ? List.of() : dto.getDataNeeds());
        plan.put("hypotheses", dto.getHypotheses() == null ? List.of() : dto.getHypotheses());
        plan.put("toolHints", json.valueToTree(dto.getToolHints() == null ? List.of() : dto.getToolHints()));
        // R2 确定性兜底:信号词命中且无背景型问题时自动补一条(LLM 判断为主,此处只兜底;幂等)
        ensureBackgroundQuestion(plan, topic, contentDescription);

        String planJson = json.writeValueAsString(plan);
        UpdateWrapper<ArticleBriefEntity> uw = new UpdateWrapper<ArticleBriefEntity>().eq("id", briefId)
                .set("research_plan", planJson)
                .set("plan_status", "READY")
                .set("ai_model", cr.model())
                .set("token_usage", cr.totalTokens());
        // reasoning 可选落库(非推理模型为 null 时不覆盖列)
        if (cr.reasoning() != null && !cr.reasoning().isBlank()) {
            uw.set("research_reasoning", cr.reasoning());
        }
        briefMapper.update(null, uw);

        b.setResearchPlan(planJson);
        b.setPlanStatus("READY");
        b.setAiModel(cr.model());
        b.setTokenUsage(cr.totalTokens());
        if (cr.reasoning() != null && !cr.reasoning().isBlank()) b.setResearchReasoning(cr.reasoning());
        log.info("研究计划生成完成 briefId={} questions={}", briefId, ((List<?>) plan.get("keyQuestions")).size());
        return b;
    }

    /** 组装 user prompt:项目创建输入(主题/内容描述/读者/字数)+ 结构化意图契约 TaskBrief。 */
    String buildUserPrompt(ArticleProjectEntity p, String topic, String contentDescription, String taskBrief) {
        StringBuilder user = new StringBuilder("主题:").append(topic).append('\n');
        if (contentDescription != null && !contentDescription.isBlank()) {
            user.append("内容描述:").append(contentDescription).append('\n');
        }
        if (p.getAudience() != null && !p.getAudience().isBlank()) {
            user.append("目标读者:").append(p.getAudience()).append('\n');
        }
        // 目标字数缺省回退 1500(口径对齐 BriefService/DeepWriterService/VersionService)
        user.append("目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        user.append("\n意图契约(TaskBrief JSON,结构化意图;研究规划只读意图,不产出意图澄清问题):\n")
                .append(taskBrief).append('\n');
        return user.toString();
    }

    /** 车型名录注入(失败降级为空串,prompt 会提示允许自由提问但不得编造)。 */
    private String catalogOrEmpty() {
        try {
            return carModelService.list().stream()
                    .map(m -> m.getName() + (m.getPriceRange() == null || m.getPriceRange().isBlank()
                            ? "" : "（" + m.getPriceRange() + "）"))
                    .collect(java.util.stream.Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("车库名录获取失败,研究规划退化为不注车型名录: {}", e.getMessage());
            return "";
        }
    }

    private ArticleBriefEntity requireBrief(Long projectId, Long briefId) {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null || projectId == null || !projectId.equals(b.getProjectId())) {
            throw new IllegalArgumentException("brief 不存在");
        }
        return b;
    }

    // ==================== R2 背景题判定与确定性兜底(自 ClarifyService 逐字迁移) ====================

    /** R2 背景/来龙去脉型主题信号词:命中则主题应含至少一条背景型问题。 */
    private static final String[] BACKGROUND_SIGNALS = {
            "发布", "宣布", "建成", "落成", "完成", "启用", "战略", "计划", "规划", "政策",
            "里程碑", "首个", "突破", "布局", "进军"};

    /** R2 背景型问题检测词:现有 keyQuestions 命中任一即视为已覆盖背景维度(不重复补题)。 */
    private static final String[] BACKGROUND_TERMS = {
            "背景", "战略", "规划", "目标", "意义", "来龙去脉", "发展历程", "布局", "为什么", "如何演变"};

    /**
     * 背景/来龙去脉型问题判定(09-27-brief-writing-linkage-fix R2;C2 自 ClarifyService 迁移)。
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

    // ==================== 10-04-serper-provider A:时效题判定(垂直路由) ====================

    /** A(10-04)时效信号词:命中则问题视为时效型,可路由 Serper {@code /news} 垂直。 */
    private static final String[] TIME_SENSITIVE_SIGNALS = {
            "最新", "近期", "最近", "现在", "今年", "当前", "动态", "发布"};

    /**
     * 时效型问题判定(10-04-serper-provider A-R3/§5.1):命中 {@link #TIME_SENSITIVE_SIGNALS} 任一即为时效型。
     *
     * <p>用途:Serper 垂直路由决策——时效题走 {@code /news}(返回 {@code date}/{@code source}),
     * 其余走 {@code /search}。与 {@link #isBackgroundQuestion} 同处、同为纯字符串判定、无副作用、包级可见。
     * <b>不做时效性计算</b>(R4b 默认 off),仅决定垂直选择。
     */
    static boolean isTimeSensitiveQuestion(String question) {
        if (question == null || question.isBlank()) return false;
        return java.util.Arrays.stream(TIME_SENSITIVE_SIGNALS).anyMatch(question::contains);
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
        if (hints instanceof ArrayNode arr) {
            JsonNodeFactory nf = JsonNodeFactory.instance;
            ObjectNode hint = nf.objectNode();
            hint.put("question", question);
            ArrayNode tools = hint.putArray("tools");
            tools.add("KB");
            tools.add("WEB");
            arr.add(hint);
        }
    }
}
