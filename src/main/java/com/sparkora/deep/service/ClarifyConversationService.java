package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.ClarifyNextDto;
import com.sparkora.ai.TaskBriefDto;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.service.ProjectStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C1 意图澄清对话服务（10-03-gen-cognitive-redesign，design §4.2）。
 *
 * <p>把「LLM 一次性定长澄清问卷」改造为缺口驱动的多轮对话：每轮返回「下一问」或「收敛」，
 * 以信息充分性为收敛条件，产出结构化 TaskBrief 作为下游唯一输入。
 *
 * <p>状态机：brief 侧 {@code clarify_status} = ASKING → CONVERGED / ABORTED；部分唯一索引
 * {@code uq_brief_clarify_asking} 保证同项目至多一条 ASKING 会话。本服务不触碰项目状态机
 * （澄清不推进项目 {@code status}，保持 DRAFT），状态写权仍归 {@link ProjectStatusService}。
 *
 * <p>事务边界（对齐 {@link ClarifyService}）：短事务写占位/产物，LLM 调用无事务；
 * 失败不产生残留（start 失败删占位、answer 失败保留已有会话不落错误状态）。
 */
@Slf4j
@Service
public class ClarifyConversationService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleProjectMapper projectMapper;
    /** 车型知识库名录：车型/竞品选项只能取真实车库（失败降级不注入）。 */
    private final com.sparkora.car.service.CarModelService carModelService;

    /** 陈旧 ASKING 清理阈值（与状态服务同款 10min 口径）。 */
    private static final long STALE_ASKING_MS = ProjectStatusService.STALE_GENERATING_MS;

    /** 全部槽位（顺序即展示序）。 */
    private static final List<String> ALL_SLOTS = List.of(
            "purpose", "audience", "tone", "angles", "mustCover", "mustAvoid", "successCriteria", "lengthTarget");
    /** 必要槽位：任一未填则硬兜底强制续问（即使 LLM 判 converged=true）。 */
    private static final List<String> REQUIRED_SLOTS = List.of("purpose", "audience", "mustCover");
    /** 多值槽位：value 存 JSON 数组。 */
    private static final Set<String> LIST_SLOTS = Set.of("angles", "mustCover", "mustAvoid");
    /** 槽位展示名。 */
    private static final Map<String, String> SLOT_LABELS = Map.of(
            "purpose", "写作目的",
            "audience", "目标读者",
            "tone", "语气风格",
            "angles", "切入角度",
            "mustCover", "必须覆盖",
            "mustAvoid", "必须避免",
            "successCriteria", "成功标准",
            "lengthTarget", "目标字数");

    private static final String SRC_USER = "USER";
    private static final String SRC_PICKED = "PICKED";
    private static final String SRC_DEFAULT = "DEFAULT";
    private static final String SRC_INFERRED = "INFERRED";
    private static final Set<String> SOURCES = Set.of(SRC_USER, SRC_PICKED, SRC_DEFAULT, SRC_INFERRED);

    public ClarifyConversationService(AiClient aiClient, ObjectMapper json,
                                       ArticleBriefMapper briefMapper, ArticleProjectMapper projectMapper,
                                       com.sparkora.car.service.CarModelService carModelService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.projectMapper = projectMapper;
        this.carModelService = carModelService;
    }

    // ==================== 对外：start / answer / converge / abort ====================

    /**
     * 启动澄清会话（同步，含首题 LLM 生成）：清理陈旧 ASKING → 落 ASKING 占位 brief → LLM 生成第一题并写回 session。
     * 撞部分唯一索引 uq_brief_clarify_asking → 409；LLM 失败删占位不留残余。
     *
     * @return {briefId, stage:"ASKING", question, session}
     */
    public Map<String, Object> start(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (p.getTopic() == null || p.getTopic().isBlank()) throw new IllegalArgumentException("缺少主题");

        // 陈旧自愈：进程中途死亡遗留的 ASKING 行（超阈值）物理删除后放行重触发
        briefMapper.delete(new QueryWrapper<ArticleBriefEntity>()
                .eq("project_id", projectId)
                .eq("clarify_status", "ASKING")
                .lt("created_at", LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_ASKING_MS))));

        ObjectNode session = emptySession();
        ArticleBriefEntity b = new ArticleBriefEntity();
        b.setProjectId(projectId);
        b.setGenMode("DEEP");
        b.setClarifyStatus("ASKING");
        b.setClarifySession(session.toString());
        b.setCreatedAt(LocalDateTime.now());
        try {
            briefMapper.insert(b);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("该项目已有进行中的意图澄清会话，请先继续或中止", e);
        }

        try {
            ClarifyNextDto dto = callNext(buildConversationSystem(), buildConversationUserPrompt(p, session, null, null));
            if (dto != null && dto.getNextQuestion() != null) {
                session.set("currentQuestion", questionNode(dto.getNextQuestion()));
            } else {
                session.set("currentQuestion", questionNode(fallbackQuestion(session)));
            }
            writeColumns(b.getId(), session.toString(), null, null);
        } catch (Exception e) {
            // 失败不产生残留：删除 ASKING 占位行，前端可重试
            log.warn("澄清会话首题生成失败 project={}: {}", projectId, e.getMessage());
            try {
                briefMapper.deleteById(b.getId());
            } catch (Exception de) {
                log.warn("清理澄清占位行失败 briefId={}: {}", b.getId(), de.getMessage());
            }
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", b.getId());
        out.put("stage", "ASKING");
        out.put("question", session.get("currentQuestion"));
        out.put("session", session);
        return out;
    }

    /**
     * 回答当前问题并推进一轮：追加回合 → 落定本轮答案对应槽位 → LLM 决定下一问或收敛。
     * 必要槽位硬兜底：任一未填则不得收敛；LLM 两次失败时保留会话、降级为确定性下一问（不落错误状态）。
     *
     * @return {briefId, converged, question?, taskBrief?, session}
     */
    public Map<String, Object> answer(Long projectId, Long briefId, String questionId, String answer) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = requireAsking(projectId, briefId);
        ObjectNode session = parseSession(b.getClarifySession());
        JsonNode current = session.get("currentQuestion");
        if (current == null || current.isNull()) {
            throw new IllegalStateException("当前没有待回答的问题，会话可能已收敛或中止");
        }
        if (questionId != null && !questionId.isBlank()
                && !questionId.equals(current.path("id").asText(""))) {
            throw new IllegalStateException("问题已过期，请刷新后重试");
        }

        // 先落库用户回答：记录回合 + 该题对应槽位（确定性，保证必要槽位不因 LLM 失败而丢失）
        String qText = current.path("text").asText("");
        String slotId = current.path("slotId").asText("");
        String qType = current.path("type").asText("input");
        appendTurn(session, questionId, qText, answer);
        if (!slotId.isBlank() && answer != null && !answer.isBlank()) {
            String source = isPicked(qType, current, answer) ? SRC_PICKED : SRC_USER;
            applySlot(session, slotId, answer, source, 1.0);
        }

        // LLM 决定下一问 / 收敛；失败降级（不破坏会话状态）
        ClarifyNextDto dto = null;
        try {
            dto = callNext(buildConversationSystem(), buildConversationUserPrompt(p, session, qText, answer));
        } catch (Exception e) {
            log.warn("澄清会话下一问生成失败,降级为确定性追问 briefId={}: {}", briefId, e.getMessage());
        }
        if (dto != null && dto.getSlotUpdates() != null) {
            for (ClarifyNextDto.SlotUpdate su : dto.getSlotUpdates()) {
                if (su == null || su.getId() == null || su.getId().isBlank()) continue;
                applySlot(session, su.getId(), su.getValue(), su.getSource(), su.getConfidence());
            }
        }
        // reasoning 透出落 session(可选;供前端/调试观察模型判断依据)
        if (dto != null && dto.getReasoning() != null && !dto.getReasoning().isBlank()) {
            session.put("reasoning", dto.getReasoning());
        }

        boolean requiredFilled = requiredFilled(session);
        boolean llmConverged = dto != null && dto.isConverged();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", briefId);

        if (llmConverged && requiredFilled) {
            // 收敛：装配 TaskBrief 落库 + status=CONVERGED
            TaskBriefDto tb = generateTaskBrief(p, session);
            ObjectNode taskBrief = assembleTaskBrief(session, tb, p);
            session.put("status", "CONVERGED");
            session.put("converged", true);
            session.set("currentQuestion", JsonNodeFactory.instance.nullNode());
            writeColumns(briefId, session.toString(), taskBrief.toString(), "CONVERGED");
            out.put("converged", true);
            out.put("taskBrief", taskBrief);
            out.put("session", session);
        } else {
            // 续问：必要槽位未齐时忽略 LLM 的 converged，强制给下一题
            ObjectNode q;
            if (dto != null && dto.getNextQuestion() != null) {
                q = questionNode(dto.getNextQuestion());
            } else {
                q = questionNode(fallbackQuestion(session));
            }
            if (llmConverged && !requiredFilled) {
                log.info("必要槽位未填,忽略 LLM 收敛判定强制续问 briefId={}", briefId);
            }
            session.put("status", "ASKING");
            session.put("converged", false);
            session.set("currentQuestion", q);
            writeColumns(briefId, session.toString(), null, null);
            out.put("converged", false);
            out.put("question", q);
            out.put("session", session);
        }
        return out;
    }

    /**
     * 强制收敛：必要槽位缺则用已有信息 + 默认（source=DEFAULT）装配 TaskBrief。
     * 已 CONVERGED 幂等返回既有 taskBrief；ABORTED/不存在 → 409/400。
     *
     * @return {briefId, taskBrief}
     */
    public Map<String, Object> converge(Long projectId, Long briefId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        String status = b.getClarifyStatus();
        if ("CONVERGED".equals(status) && b.getTaskBrief() != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("briefId", briefId);
            out.put("taskBrief", parseSession(b.getTaskBrief()));
            return out;
        }
        if (!"ASKING".equals(status)) {
            throw new IllegalStateException("会话状态为「" + status + "」，无法收敛");
        }
        ObjectNode session = parseSession(b.getClarifySession());
        TaskBriefDto tb = generateTaskBrief(p, session);   // 失败内部降级为 null
        ObjectNode taskBrief = assembleTaskBrief(session, tb, p);
        session.put("status", "CONVERGED");
        session.put("converged", true);
        session.set("currentQuestion", JsonNodeFactory.instance.nullNode());
        writeColumns(briefId, session.toString(), taskBrief.toString(), "CONVERGED");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", briefId);
        out.put("taskBrief", taskBrief);
        return out;
    }

    /** 中止会话：clarify_status=ABORTED，session.status=ABORTED（幂等）。 */
    public Map<String, Object> abort(Long projectId, Long briefId) {
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        if ("ABORTED".equals(b.getClarifyStatus())) {
            return Map.of("briefId", briefId, "status", "ABORTED");
        }
        ObjectNode session = parseSession(b.getClarifySession());
        session.put("status", "ABORTED");
        session.put("converged", false);
        session.set("currentQuestion", JsonNodeFactory.instance.nullNode());
        writeColumns(briefId, session.toString(), null, "ABORTED");
        return Map.of("briefId", briefId, "status", "ABORTED");
    }

    // ==================== LLM 调用（无事务；截断/空/非法 JSON → 提额 16384 重试一次） ====================

    /** 下一问/收敛调用：首次 8192，任何失败提额 16384 重试一次（对齐 BriefService/ClarifyService 范式）。 */
    ClarifyNextDto callNext(String system, String user) {
        try {
            return aiClient.structured(system, user, 8192, ClarifyNextDto.class).entity();
        } catch (Exception first) {
            log.warn("澄清下一问首次调用失败,提额重试(16384): {}", first.getMessage());
            return aiClient.structured(system, user
                    + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象。",
                    16384, ClarifyNextDto.class).entity();
        }
    }

    /**
     * 收敛装配调用：首次 8192，失败提额 16384 重试一次；两次均失败返回 null（服务层按会话兜底装配，降级清晰）。
     */
    TaskBriefDto generateTaskBrief(ArticleProjectEntity p, ObjectNode session) {
        String system = com.sparkora.ai.PromptTemplateLoader.render("clarify/taskbrief-system.st",
                Map.of("schema", AiClient.jsonSchema(TaskBriefDto.class)));
        String user = buildTaskBriefUserPrompt(p, session);
        try {
            return aiClient.structured(system, user, 8192, TaskBriefDto.class).entity();
        } catch (Exception first) {
            log.warn("TaskBrief 首次生成失败,提额重试(16384): {}", first.getMessage());
            try {
                return aiClient.structured(system, user
                        + "\n注意:上次输出失败,请只输出一个完整、合法的 JSON 对象。", 16384, TaskBriefDto.class).entity();
            } catch (Exception second) {
                log.warn("TaskBrief 两次生成均失败,按会话已有信息兜底装配: {}", second.getMessage());
                return null;
            }
        }
    }

    private String buildConversationSystem() {
        return com.sparkora.ai.PromptTemplateLoader.render("clarify/conversation-system.st",
                Map.of("schema", AiClient.jsonSchema(ClarifyNextDto.class),
                        "catalog", catalogOrEmpty()));
    }

    /** 组装对话 user prompt：创建输入 + 已有槽位 + 对话历史 + 本轮问答。 */
    String buildConversationUserPrompt(ArticleProjectEntity p, ObjectNode session, String questionText, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("主题:").append(p.getTopic() == null ? "" : p.getTopic()).append('\n');
        if (p.getContentDescription() != null && !p.getContentDescription().isBlank()) {
            sb.append("内容描述:").append(p.getContentDescription()).append('\n');
        }
        if (p.getAudience() != null && !p.getAudience().isBlank()) {
            sb.append("目标读者(创建时填写):").append(p.getAudience()).append('\n');
        }
        sb.append("目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        sb.append("\n已有槽位:\n").append(slotsSummary(session));
        JsonNode turns = session.path("turns");
        if (turns.isArray() && !turns.isEmpty()) {
            sb.append("\n对话历史:\n");
            for (JsonNode t : turns) {
                sb.append(t.path("idx").asInt()).append(". Q: ").append(t.path("question").asText("")).append('\n');
                sb.append("   A: ").append(t.path("answer").asText("")).append('\n');
            }
        }
        if (questionText != null && !questionText.isBlank()) {
            sb.append("\n刚回答的问题: ").append(questionText).append('\n');
            sb.append("用户回答: ").append(answer == null ? "" : answer).append('\n');
        } else {
            sb.append("\n尚无对话,请给出信息增益最高的第一题。\n");
        }
        sb.append("请判断信息是否充分,给出下一问或置 converged=true。");
        return sb.toString();
    }

    private String buildTaskBriefUserPrompt(ArticleProjectEntity p, ObjectNode session) {
        StringBuilder sb = new StringBuilder();
        sb.append("主题:").append(p.getTopic() == null ? "" : p.getTopic()).append('\n');
        if (p.getContentDescription() != null && !p.getContentDescription().isBlank()) {
            sb.append("内容描述:").append(p.getContentDescription()).append('\n');
        }
        sb.append("默认目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        sb.append("\n槽位汇总:\n").append(slotsSummary(session));
        JsonNode turns = session.path("turns");
        if (turns.isArray() && !turns.isEmpty()) {
            sb.append("\n澄清回合:\n");
            for (JsonNode t : turns) {
                sb.append("- Q: ").append(t.path("question").asText("")).append('\n');
                sb.append("  A: ").append(t.path("answer").asText("")).append('\n');
            }
        }
        return sb.toString();
    }

    /** 车型名录注入（失败降级为空串，prompt 会提示允许自由提问但不得编造）。 */
    private String catalogOrEmpty() {
        try {
            return carModelService.list().stream()
                    .map(m -> m.getName() + (m.getPriceRange() == null || m.getPriceRange().isBlank()
                            ? "" : "（" + m.getPriceRange() + "）"))
                    .collect(java.util.stream.Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("车库名录获取失败,澄清对话退化为不注车型名录: {}", e.getMessage());
            return "";
        }
    }

    // ==================== TaskBrief 装配 ====================

    /**
     * 依据会话槽位元数据 + LLM TaskBriefDto 装配结构化契约。每个标量槽位包成
     * {@code {value,source,confidence}}，多值槽位为 {@code [{value,source,confidence}]}；
     * 会话缺失的槽位用 DTO 值（source=DEFAULT）或内置默认兜底。
     */
    ObjectNode assembleTaskBrief(ObjectNode session, TaskBriefDto dto, ArticleProjectEntity p) {
        ObjectNode tb = JsonNodeFactory.instance.objectNode();
        for (String id : ALL_SLOTS) {
            JsonNode slot = findSlot(session, id);
            if (LIST_SLOTS.contains(id)) {
                List<String> values = slotValues(slot);
                if (values.isEmpty() && dto != null) values = dtoList(id, dto);
                String source = slotSource(slot);
                double confidence = slotConfidence(slot, values.isEmpty() ? 0.2 : 0.4);
                if (values.isEmpty() && REQUIRED_SLOTS.contains(id)) {
                    values = List.of(defaultText(id));
                    source = SRC_DEFAULT;
                    confidence = 0.2;
                }
                ArrayNode arr = tb.putArray(id);
                for (String v : values) {
                    ObjectNode item = arr.addObject();
                    item.put("value", v);
                    item.put("source", source);
                    item.put("confidence", confidence);
                }
            } else {
                String value = slotValue(slot);
                if ((value == null || value.isBlank()) && dto != null) value = dtoScalar(id, dto);
                String source = slotSource(slot);
                double confidence = slotConfidence(slot, (value == null || value.isBlank()) ? 0.2 : 0.4);
                if ((value == null || value.isBlank()) && REQUIRED_SLOTS.contains(id)) {
                    value = defaultText(id);
                    source = SRC_DEFAULT;
                    confidence = 0.2;
                }
                ObjectNode item = tb.putObject(id);
                item.put("value", value == null ? "" : value);
                item.put("source", source);
                item.put("confidence", confidence);
            }
        }
        ArrayNode meta = tb.putArray("slotMeta");
        for (String id : ALL_SLOTS) {
            JsonNode slot = findSlot(session, id);
            ObjectNode m = meta.addObject();
            m.put("id", id);
            m.put("filled", slotFilled(session, id));
            m.put("source", slotSource(slot));
        }
        return tb;
    }

    private List<String> dtoList(String id, TaskBriefDto dto) {
        List<String> v = switch (id) {
            case "angles" -> dto.getAngles();
            case "mustCover" -> dto.getMustCover();
            case "mustAvoid" -> dto.getMustAvoid();
            default -> null;
        };
        List<String> out = new ArrayList<>();
        if (v != null) for (String s : v) if (s != null && !s.isBlank()) out.add(s.trim());
        return out;
    }

    private String dtoScalar(String id, TaskBriefDto dto) {
        return switch (id) {
            case "purpose" -> dto.getPurpose();
            case "audience" -> dto.getAudience();
            case "tone" -> dto.getTone();
            case "successCriteria" -> dto.getSuccessCriteria();
            case "lengthTarget" -> dto.getLengthTarget() == null ? null : String.valueOf(dto.getLengthTarget());
            default -> null;
        };
    }

    private static String defaultText(String id) {
        return switch (id) {
            case "purpose" -> "让读者了解该主题的核心信息";
            case "audience" -> "关注该领域的普通读者";
            case "mustCover" -> "主题的核心信息";
            default -> "";
        };
    }

    // ==================== 会话 JSON 操作（容错，不抛） ====================

    /** 解析会话 JSON；畸形/空 → 返回空会话（不抛，保证降级清晰）。 */
    ObjectNode parseSession(String raw) {
        if (raw == null || raw.isBlank()) return emptySession();
        try {
            JsonNode n = json.readTree(raw);
            if (n instanceof ObjectNode on) return on;
        } catch (Exception e) {
            log.warn("会话 JSON 解析失败,回退空会话: {}", e.getMessage());
        }
        return emptySession();
    }

    /** 空会话：status=ASKING + slots/turns 空数组。 */
    ObjectNode emptySession() {
        ObjectNode s = JsonNodeFactory.instance.objectNode();
        s.put("status", "ASKING");
        s.put("converged", false);
        s.putArray("slots");
        s.putArray("turns");
        s.set("currentQuestion", JsonNodeFactory.instance.nullNode());
        return s;
    }

    private void appendTurn(ObjectNode session, String questionId, String questionText, String answer) {
        ArrayNode turns = session.withArray("turns");
        ObjectNode t = turns.addObject();
        t.put("idx", turns.size());
        t.put("questionId", questionId == null ? "" : questionId);
        t.put("question", questionText == null ? "" : questionText);
        t.put("answer", answer == null ? "" : answer);
        t.put("at", LocalDateTime.now().toString());
    }

    /**
     * 写入/合并一个槽位增量。多值槽位合并去重保序；标量覆盖。source 白名单外归 INFERRED，
     * confidence 截到 [0,1]；空 value 忽略。
     */
    void applySlot(ObjectNode session, String id, String value, String source, Double confidence) {
        if (id == null || id.isBlank()) return;
        String sid = id.trim();
        if (!ALL_SLOTS.contains(sid)) return;
        if (value == null || value.isBlank()) return;
        float conf = clampConfidence(confidence);
        String src = (source != null && SOURCES.contains(source.trim().toUpperCase()))
                ? source.trim().toUpperCase() : SRC_INFERRED;
        ObjectNode slot = findSlot(session, sid);
        if (slot == null) {
            slot = session.withArray("slots").addObject();
            slot.put("id", sid);
            slot.put("label", SLOT_LABELS.getOrDefault(sid, sid));
        }
        if (LIST_SLOTS.contains(sid)) {
            LinkedHashSet<String> values = new LinkedHashSet<>(slotValues(slot));
            values.addAll(parseSlotValues(value));
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            for (String v : values) arr.add(v);
            slot.set("value", arr);
        } else {
            slot.put("value", value.trim());
        }
        slot.put("source", src);
        slot.put("confidence", conf);
        slot.put("done", true);
    }

    /** 从槽位节点取标量值；数组节点取首元素（兼容误写）。 */
    private static String slotValue(JsonNode slot) {
        if (slot == null || slot.isNull()) return null;
        JsonNode v = slot.get("value");
        if (v == null || v.isNull()) return null;
        if (v.isArray()) return v.isEmpty() ? null : v.get(0).asText();
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    /** 从槽位节点取多值列表。 */
    private static List<String> slotValues(JsonNode slot) {
        List<String> out = new ArrayList<>();
        if (slot == null || slot.isNull()) return out;
        JsonNode v = slot.get("value");
        if (v == null || v.isNull()) return out;
        if (v.isArray()) {
            for (JsonNode n : v) {
                String s = n.asText();
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        } else {
            String s = v.asText();
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** 解析 LLM 给出的槽位值：JSON 数组文本 → 多值；否则按常见分隔符拆分。 */
    static List<String> parseSlotValues(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                JsonNode n = new ObjectMapper().readTree(s);
                if (n.isArray()) {
                    for (JsonNode e : n) {
                        String v = e.asText();
                        if (v != null && !v.isBlank()) out.add(v.trim());
                    }
                    if (!out.isEmpty()) return out;
                }
            } catch (Exception ignore) {
                // 非合法数组 → 按单值处理
            }
        }
        if (s.contains("、") || s.contains(",") || s.contains("，")) {
            for (String part : s.split("[、,，]")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        } else {
            out.add(s);
        }
        return out;
    }

    private static float clampConfidence(Double c) {
        if (c == null) return 0.5f;
        double d = c;
        if (d < 0) d = 0;
        if (d > 1) d = 1;
        return (float) d;
    }

    private static String slotSource(JsonNode slot) {
        if (slot == null || slot.isNull()) return SRC_DEFAULT;
        String s = slot.path("source").asText("");
        return SOURCES.contains(s) ? s : SRC_DEFAULT;
    }

    private static double slotConfidence(JsonNode slot, double fallback) {
        if (slot == null || slot.isNull()) return fallback;
        JsonNode c = slot.get("confidence");
        if (c == null || !c.isNumber()) return fallback;
        return c.asDouble();
    }

    /** session.slots 中找槽位节点；不存在返回 null（不创建）。 */
    private static ObjectNode findSlot(ObjectNode session, String id) {
        JsonNode slots = session.get("slots");
        if (slots == null || !slots.isArray()) return null;
        for (JsonNode s : slots) {
            if (s.isObject() && id.equals(s.path("id").asText(""))) return (ObjectNode) s;
        }
        return null;
    }

    /** 必要槽位是否全部有值。 */
    boolean requiredFilled(ObjectNode session) {
        for (String id : REQUIRED_SLOTS) {
            if (!slotFilled(session, id)) return false;
        }
        return true;
    }

    private static boolean slotFilled(ObjectNode session, String id) {
        JsonNode slot = findSlot(session, id);
        if (slot == null) return false;
        if (LIST_SLOTS.contains(id)) return !slotValues(slot).isEmpty();
        String v = slotValue(slot);
        return v != null && !v.isBlank();
    }

    /** 已有槽位摘要（注入 prompt）。 */
    private String slotsSummary(ObjectNode session) {
        StringBuilder sb = new StringBuilder();
        for (String id : ALL_SLOTS) {
            JsonNode slot = findSlot(session, id);
            String label = SLOT_LABELS.getOrDefault(id, id);
            if (slot == null) {
                sb.append("- ").append(label).append(':').append("（未填）").append('\n');
            } else {
                String val = LIST_SLOTS.contains(id)
                        ? String.join("、", slotValues(slot)) : slotValue(slot);
                sb.append("- ").append(label).append(':').append(val == null ? "（未填）" : val)
                        .append("（").append(slotSource(slot)).append(", ").append(slotConfidence(slot, 0.5)).append("）").append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 确定性兜底下一问：优先第一个未填必要槽位；必要槽位齐全时取第一个未填可选槽位；
     * 全部已填则给通用补充题。保证必要槽位硬兜底场景下即使 LLM 失败也能继续（降级清晰）。
     */
    ClarifyNextDto.Question fallbackQuestion(ObjectNode session) {
        for (String id : REQUIRED_SLOTS) {
            if (!slotFilled(session, id)) return defaultQuestion(id);
        }
        for (String id : ALL_SLOTS) {
            if (!REQUIRED_SLOTS.contains(id) && !slotFilled(session, id)) return defaultQuestion(id);
        }
        return defaultQuestion("__supplement__");
    }

    private static ClarifyNextDto.Question defaultQuestion(String slotId) {
        ClarifyNextDto.Question q = new ClarifyNextDto.Question();
        q.setId(slotId);
        q.setSlotId(slotId);
        q.setRequired(REQUIRED_SLOTS.contains(slotId));
        switch (slotId) {
            case "purpose" -> {
                q.setText("这篇文章的主要写作目的是什么？");
                q.setType("input");
            }
            case "audience" -> {
                q.setText("这篇文章主要写给谁看？");
                q.setType("single");
                q.setOptions(List.of("潜在购车用户", "汽车行业从业者", "品牌粉丝", "大众读者"));
            }
            case "mustCover" -> {
                q.setText("有哪些内容点是必须覆盖的？（可多选/补充）");
                q.setType("input");
            }
            case "tone" -> {
                q.setText("希望采用什么样的语气风格？");
                q.setType("single");
                q.setOptions(List.of("专业理性", "轻松口语", "客观中立", "热情推荐"));
            }
            default -> {
                q.setText("还有其他需要补充的信息吗？");
                q.setType("input");
            }
        }
        return q;
    }

    /** DTO Question → 前端 JSON 节点。 */
    private static ObjectNode questionNode(ClarifyNextDto.Question q) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("id", q.getId() == null ? "" : q.getId());
        n.put("text", q.getText() == null ? "" : q.getText());
        n.put("type", q.getType() == null || q.getType().isBlank() ? "input" : q.getType());
        if (q.getOptions() != null && !q.getOptions().isEmpty()) {
            ArrayNode opts = n.putArray("options");
            for (String o : q.getOptions()) if (o != null && !o.isBlank()) opts.add(o.trim());
        }
        n.put("required", q.getRequired() == null || q.getRequired());
        n.put("slotId", q.getSlotId() == null ? "" : q.getSlotId());
        return n;
    }

    /** 判断题答案是否来自选项（PICKED）还是自由输入（USER）。 */
    private static boolean isPicked(String type, JsonNode question, String answer) {
        if (!"single".equals(type) && !"multi".equals(type)) return false;
        JsonNode opts = question.get("options");
        if (opts == null || !opts.isArray()) return false;
        for (String a : parseSlotValues(answer)) {
            for (JsonNode o : opts) {
                if (a.equals(o.asText())) return true;
            }
        }
        return false;
    }

    // ==================== 持久化 / 校验 ====================

    /** 仅写指定列（UpdateWrapper 显式 set，避免 updateById 全字段回写覆盖并发列）。 */
    private void writeColumns(Long briefId, String session, String taskBrief, String clarifyStatus) {
        UpdateWrapper<ArticleBriefEntity> uw = new UpdateWrapper<ArticleBriefEntity>().eq("id", briefId);
        if (session != null) uw.set("clarify_session", session);
        if (taskBrief != null) uw.set("task_brief", taskBrief);
        if (clarifyStatus != null) uw.set("clarify_status", clarifyStatus);
        briefMapper.update(null, uw);
    }

    private ArticleBriefEntity requireBrief(Long projectId, Long briefId) {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null || !projectId.equals(b.getProjectId())) {
            throw new IllegalArgumentException("brief 不存在");
        }
        return b;
    }

    private ArticleBriefEntity requireAsking(Long projectId, Long briefId) {
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        if (!"ASKING".equals(b.getClarifyStatus())) {
            throw new IllegalStateException("会话状态为「" + b.getClarifyStatus() + "」，无法继续回答");
        }
        return b;
    }
}
