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

    /** 生成中状态超过该时长视为陈旧(JVM 中途死亡/重启残留),允许重新触发以自愈。 */
    private static final long STALE_GENERATING_MS = 10 * 60 * 1000L;

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleProjectMapper projectMapper;
    /** 车型知识库名录(S9 修复):反问问题必须基于真实车库车型,而非模型凭主题猜测。 */
    private final com.sparkora.car.service.CarModelService carModelService;
    // 自注入代理,确保 @Async 生效(start 内 this.runAsync 不会走代理)
    @Autowired
    @Lazy
    private ClarifyService self;

    public ClarifyService(AiClient aiClient, ObjectMapper json,
                          ArticleBriefMapper briefMapper, ArticleProjectMapper projectMapper,
                          com.sparkora.car.service.CarModelService carModelService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.projectMapper = projectMapper;
        this.carModelService = carModelService;
    }

    /**
     * 启动研究计划生成(同步毫秒级,202 语义):落 PLANNING 占位行后立即返回,后台 self.runAsync 生成。
     * @return 占位 brief(id 供前端轮询 /deep/status 引用)
     */
    public ArticleBriefEntity start(Long projectId, String topic, String extraInfo) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("缺少主题");

        // 清理陈旧占位:进程中途死亡遗留的 PLANNING 行(超过阈值)删除,放行重新触发以自愈
        briefMapper.delete(new QueryWrapper<ArticleBriefEntity>()
                .eq("project_id", projectId)
                .eq("plan_status", "PLANNING")
                .lt("created_at", LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS))));

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
        self.runAsync(b.getId(), topic, extraInfo);
        return b;
    }

    /**
     * 异步生成研究计划与澄清问题(由 self 代理调用)。
     * 成功回写 research_plan/clarify_questions/ai_model/token_usage + plan_status=READY;
     * 失败删除占位行(保持「失败无残留」)并写 project.last_brief_error,状态保持 DRAFT。
     */
    @Async
    public void runAsync(Long briefId, String topic, String extraInfo) {
        try {
            PlanResult r = generatePlan(topic, extraInfo);
            ArticleBriefEntity b = briefMapper.selectById(briefId);
            if (b == null) return;   // 占位行已被并发清理(如失败重试),丢弃结果
            b.setResearchPlan(r.researchPlan());
            b.setClarifyQuestions(r.questions());
            b.setAiModel(r.model());
            b.setTokenUsage(r.totalTokens());
            b.setPlanStatus("READY");
            briefMapper.updateById(b);
            // 成功后清空 last_brief_error(与 BriefService 一致:失败原因成功后清空,避免重试成功后仍显示旧错误)
            try {
                ArticleProjectEntity fresh = projectMapper.selectById(b.getProjectId());
                if (fresh != null && fresh.getLastBriefError() != null) {
                    fresh.setLastBriefError(null);
                    fresh.setUpdatedAt(LocalDateTime.now());
                    projectMapper.updateById(fresh);
                }
            } catch (Exception pe) {
                log.warn("清空 lastBriefError 失败 briefId={}: {}", briefId, pe.getMessage());
            }
            log.info("研究计划生成完成 briefId={}", briefId);
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            log.warn("研究计划异步生成失败 briefId={}: {}", briefId, reason);
            // 先取 projectId(删除占位行后 brief 不可再查),再删 PLANNING 占位行(失败无残留,/deep/status 自然回 NONE)
            Long projectId = null;
            try {
                ArticleBriefEntity b = briefMapper.selectById(briefId);
                if (b != null) {
                    projectId = b.getProjectId();
                    if ("PLANNING".equals(b.getPlanStatus())) briefMapper.deleteById(briefId);
                }
            } catch (Exception de) {
                log.warn("清理研究计划占位行失败 briefId={}: {}", briefId, de.getMessage());
            }
            // 失败原因落项目(重取 + 判 null,防覆盖生成期间其他字段变更/项目被并发删除)
            try {
                ArticleProjectEntity fresh = projectId == null ? null : projectMapper.selectById(projectId);
                if (fresh != null) {
                    fresh.setLastBriefError(reason);
                    fresh.setUpdatedAt(LocalDateTime.now());
                    projectMapper.updateById(fresh);
                }
            } catch (Exception pe) {
                log.warn("写入 lastBriefError 失败 briefId={}: {}", briefId, pe.getMessage());
            }
        }
    }

    /** LLM 生成主体:返回研究计划与澄清问题(不落库),供异步 runAsync 调用。 */
    private PlanResult generatePlan(String topic, String extraInfo) throws Exception {
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
        String system = """
                你是汽车内容创作的研究规划专家。根据文章主题,产出研究计划与用户澄清问题。
                只输出 JSON 对象:
                {
                  "keyQuestions": ["需要研究的关键问题(3-4 条,每条具体可查)"],
                  "dataNeeds": ["需要的数据(如:价格/尺寸/竞品参数)"],
                  "hypotheses": ["初步假设(可被研究推翻)"],
                  "toolHints": [{"question": "与 keyQuestions 一一对应", "tools": ["KB","WEB"]}],
                  "questions": [
                    {"q": "澄清问题", "type": "input|single|multi", "options": ["single/multi 时的选项"], "required": true}
                  ]
                }
                questions 规则:
                - 3~5 个,只问影响事实与立场的问题(目标读者/对比竞品/立场倾向/期望篇幅)
                - 不问语气风格(风格库职责);type=single|multi 必须给 options;读者/篇幅可默认,竞品对比尽量问
                - 对比竞品类问题(问句含「对比/竞品/比较/竞对」语义)必须 type=multi:对比竞品天然是多选,选项给 2~4 个竞品 + 「不对比」兜底项;type 只能是 multi,禁止 single
                - 车型知识库名录(唯一真实车型来源,车型/竞品/对比类问题的 options 只能从中选,不得编造名录外的车型):
                  %s
                - 主题指向某款或某系列车型时,必须至少有一道题让用户确认写作锚点车型:options 覆盖名录中名称含该系列词的全部车型,type=multi
                - 主题未指向具体车型时,竞品题的 options 也从名录中选(必须含「不对比」兜底项)
                """.formatted(catalog.isBlank() ? "(车库暂无车型数据,允许自由提问,但不得编造具体车型名)" : catalog);
        StringBuilder user = new StringBuilder("主题:").append(topic).append('\n');
        if (extraInfo != null && !extraInfo.isBlank()) {
            user.append("用户补充:").append(extraInfo).append('\n');
        }
        AiClient.ChatResult cr = aiClient.chatJson(system, user.toString(), 2048);
        // AI 输出 JSON 容错:剥围栏+转义字符串内裸控制字符(统一走 AiClient.sanitizeAiJson)
        JsonNode node = json.readTree(AiClient.sanitizeAiJson(cr.content()));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("keyQuestions", toArray(node.path("keyQuestions")));
        plan.put("dataNeeds", toArray(node.path("dataNeeds")));
        plan.put("hypotheses", toArray(node.path("hypotheses")));
        plan.put("toolHints", node.path("toolHints"));
        String questions = node.path("questions").toString();
        String normalized = normalizeQuestions(questions);
        return new PlanResult(json.writeValueAsString(plan), normalized, cr.model(), cr.totalTokens());
    }

    /** generatePlan 产物(计划 JSON / 归一化问题 JSON / 模型 / token)。 */
    private record PlanResult(String researchPlan, String questions, String model, int totalTokens) {}

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