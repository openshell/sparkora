package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.BlueprintDto;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C3 写作蓝图服务（10-03-gen-cognitive-redesign，design §3/§4.3/§5）。
 *
 * <p>把简报从「事实手册摘要」升级为**写作蓝图**：基于 C1 的 {@code task_brief} + C2 的
 * {@code research_plan} + {@code fact_sheet} 调用 LLM 产出 thesis + 论证结构 + evidenceMap +
 * narrativeArc + constraints + gaps，并设置**人工评审门**
 * （{@code blueprint_status=REVIEWING} → 用户编辑/确认 → {@code CONFIRMED} 才解锁写作）。
 *
 * <p><b>确定性优先</b>：evidenceMap 的 {@code entryKeys} 以 fact_sheet.entries[].key 为白名单过滤，
 * {@code coverage}（COVERED/PARTIAL/MISSING）与质量信号
 * （argumentDensity/evidenceCoverage/gapCount/taskBriefConsistency）由服务层本地计算，
 * **不采用模型自由发挥值**（design §4.3、§6）——保证可评估、可复现。MISSING/PARTIAL 论点保留但入 gaps。
 *
 * <p><b>不写项目状态机</b>：{@code status}/{@code last_*_error} 写权仍归 {@code ProjectStatusService}；
 * 本服务只按显式列写 brief 产物（{@code UpdateWrapper} 单列 set，避免 {@code updateById} 全量回写覆盖并发列），
 * 不改 {@code plan_status}。重新生成会把 {@code blueprint_status} 覆盖回 {@code REVIEWING}
 * （确认后重生成需重新确认）。
 */
@Slf4j
@Service
public class BlueprintService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleProjectMapper projectMapper;

    public BlueprintService(AiClient aiClient, ObjectMapper json,
                            ArticleBriefMapper briefMapper, ArticleProjectMapper projectMapper) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.projectMapper = projectMapper;
    }

    // ==================== 生成 ====================

    /**
     * 基于 TaskBrief + research_plan + fact_sheet 生成写作蓝图并落库（同步；失败提额重试一次后抛）。
     *
     * <p>前置：project/brief 存在且属同项目、{@code fact_sheet} 非空、{@code task_brief} 非空，
     * 否则明确 4xx/409 语义。幂等：可重复调用，覆盖已有蓝图，且 {@code blueprint_status} 覆盖回 REVIEWING。
     *
     * @return 已回写蓝图的 brief（字段已更新，供控制器直接返回）
     */
    public ArticleBriefEntity generate(Long projectId, Long briefId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = requireBrief(projectId, briefId);
        if (!"DEEP".equals(b.getGenMode())) throw new IllegalArgumentException("深度 brief 不存在");
        if (b.getFactSheet() == null || b.getFactSheet().isBlank())
            throw new IllegalStateException("事实手册尚未生成，请先完成研究");
        if (b.getTaskBrief() == null || b.getTaskBrief().isBlank())
            throw new IllegalStateException("意图澄清尚未完成，无法生成写作蓝图");

        Set<String> factKeys = factSheetKeys(b.getFactSheet());
        String system = com.sparkora.ai.PromptTemplateLoader.render("brief/blueprint-system.st",
                Map.of("schema", AiClient.jsonSchema(BlueprintDto.class)));
        String user = buildUserPrompt(p, b);

        // reasoning 模型推理 token 计入 max_tokens:首次 8192;任何失败(截断 finish_reason=length /
        // 空内容 / 非法 JSON / 反序列化失败)提额 16384 重试一次,仅两次均失败才抛
        // (范式对齐 BriefService/ResearchPlannerService/ClarifyConversationService)。
        BlueprintDto dto;
        AiClient.ChatResult cr;
        try {
            AiClient.TypedResult<BlueprintDto> tr = aiClient.structured(system, user, 8192, BlueprintDto.class);
            dto = tr.entity();
            cr = tr.chat();
        } catch (Exception first) {
            log.warn("写作蓝图首次生成失败,提额重试(16384) project={} brief={}: {}",
                    projectId, briefId, first.getMessage());
            AiClient.TypedResult<BlueprintDto> tr = aiClient.structured(system,
                    user + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象,确保字段齐全。",
                    16384, BlueprintDto.class);
            dto = tr.entity();
            cr = tr.chat();
        }

        // DTO → 可变 ObjectNode;evidenceMap 绑定白名单 + coverage/gaps/quality 确定性计算
        ObjectNode blueprint = dtoToBlueprintNode(dto);
        bindEvidenceAndGaps(blueprint, factKeys);
        BlueprintQuality quality = computeQuality(blueprint, b.getTaskBrief());
        blueprint.set("quality", qualityNode(quality));

        String blueprintJson = blueprint.toString();
        String qualityJson = qualityNode(quality).toString();

        UpdateWrapper<ArticleBriefEntity> uw = new UpdateWrapper<ArticleBriefEntity>().eq("id", briefId)
                .set("writing_blueprint", blueprintJson)
                .set("blueprint_status", "REVIEWING")
                .set("blueprint_quality", qualityJson)
                .set("ai_model", cr.model())
                .set("token_usage", cr.totalTokens());
        briefMapper.update(null, uw);

        b.setWritingBlueprint(blueprintJson);
        b.setBlueprintStatus("REVIEWING");
        b.setBlueprintQuality(qualityJson);
        b.setAiModel(cr.model());
        b.setTokenUsage(cr.totalTokens());
        log.info("写作蓝图生成完成 briefId={} sections={} evidence={} coverage={} gaps={}",
                briefId, quality.argumentDensity(), quality.evidenceCoverage(), quality.gapCount(),
                quality.taskBriefConsistency());
        return b;
    }

    // ==================== 人工评审门 ====================

    /**
     * 人工确认写作蓝图并置 {@code blueprint_status=CONFIRMED}（解锁写作）。
     *
     * <p>{@code editedBlueprintJson} 可空：非空则校验为合法 JSON，并在含 {@code evidenceMap} 时
     * 以 fact_sheet 白名单**重算 coverage/gaps/quality** 后落 {@code writing_blueprint}/{@code blueprint_quality}
     * （支持人工调整证据绑定；编辑本身不改 fact_sheet）。
     *
     * <p>幂等：已 CONFIRMED 再次 confirm 不报错（可带/不带新编辑）。{@code writing_blueprint} 为空
     * （从未生成蓝图）→ {@code IllegalStateException}。只 set 相关列，不写项目状态机。
     */
    public ArticleBriefEntity confirm(Long projectId, Long briefId, String editedBlueprintJson) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = requireBrief(projectId, briefId);

        boolean hasEdited = editedBlueprintJson != null && !editedBlueprintJson.isBlank();
        boolean hasBlueprint = b.getWritingBlueprint() != null && !b.getWritingBlueprint().isBlank();
        // 评审门语义:必须先有生成产物才可确认(编辑是对已生成蓝图的调整,不能凭空造蓝图绕过生成/质量链路)。
        if (!hasBlueprint) {
            throw new IllegalStateException("写作蓝图尚未生成，无法确认");
        }

        UpdateWrapper<ArticleBriefEntity> uw = new UpdateWrapper<ArticleBriefEntity>()
                .eq("id", briefId)
                .set("blueprint_status", "CONFIRMED");

        if (hasEdited) {
            ObjectNode edited = parseEditedBlueprint(editedBlueprintJson);
            String qualityJson = null;
            if (edited.has("evidenceMap")) {
                bindEvidenceAndGaps(edited, factSheetKeys(b.getFactSheet()));
                BlueprintQuality quality = computeQuality(edited, b.getTaskBrief());
                edited.set("quality", qualityNode(quality));
                qualityJson = qualityNode(quality).toString();
            }
            String editedJson = edited.toString();
            uw.set("writing_blueprint", editedJson);
            if (qualityJson != null) uw.set("blueprint_quality", qualityJson);
            b.setWritingBlueprint(editedJson);
            if (qualityJson != null) b.setBlueprintQuality(qualityJson);
        }
        briefMapper.update(null, uw);
        b.setBlueprintStatus("CONFIRMED");
        return b;
    }

    /** 解析人工编辑蓝图:清洗 AI JSON → 必须为对象,否则 400 语义。 */
    private ObjectNode parseEditedBlueprint(String raw) {
        try {
            JsonNode n = json.readTree(AiClient.sanitizeAiJson(raw));
            if (n instanceof ObjectNode on) return on;
            throw new IllegalArgumentException("写作蓝图 JSON 非法：需为对象");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("写作蓝图 JSON 非法：" + e.getMessage());
        }
    }

    // ==================== 确定性纯函数（可单测） ====================

    /** 蓝图结构 → 可变 ObjectNode（NON_NULL 序列化:null 字段不出现）。 */
    private ObjectNode dtoToBlueprintNode(BlueprintDto dto) {
        JsonNode n = json.valueToTree(dto);
        return n instanceof ObjectNode on ? on : JsonNodeFactory.instance.objectNode();
    }

    /**
     * 确定性 evidenceMap 绑定 + coverage/gaps：
     * <ol>
     *   <li>以 fact_sheet 的 key 集合为白名单，过滤每个证据项的 {@code entryKeys}（剔除编造/未知 key）；</li>
     *   <li>{@code coverage} 覆盖 LLM 值：有效键数==0 → MISSING；0&lt;有效键数&lt;原键数 → PARTIAL；全有效且非空 → COVERED；</li>
     *   <li>MISSING/PARTIAL 追加进 {@code gaps}（按 sectionId 去重，保留已有 gap，MISSING 论点保留但标注）。</li>
     * </ol>
     */
    static void bindEvidenceAndGaps(ObjectNode blueprint, Set<String> factKeys) {
        JsonNode em = blueprint.get("evidenceMap");
        if (!(em instanceof ArrayNode evidenceMap)) return;
        Set<String> present = gapSectionIds(blueprint);
        List<ObjectNode> computedGaps = new ArrayList<>();
        for (JsonNode item : evidenceMap) {
            if (!(item instanceof ObjectNode ev)) continue;
            List<String> original = readStringList(ev.get("entryKeys"));
            List<String> valid = new ArrayList<>();
            for (String k : original) {
                if (factKeys.contains(k) && !valid.contains(k)) valid.add(k);
            }
            ArrayNode keysNode = JsonNodeFactory.instance.arrayNode();
            for (String k : valid) keysNode.add(k);
            ev.set("entryKeys", keysNode);

            String coverage = coverageOf(original.size(), valid.size());
            ev.put("coverage", coverage);
            String sectionId = ev.path("sectionId").asText("");
            if (!"COVERED".equals(coverage) && !present.contains(sectionId)) {
                ObjectNode gap = JsonNodeFactory.instance.objectNode();
                gap.put("sectionId", sectionId);
                gap.put("reason", "PARTIAL".equals(coverage)
                        ? "部分所需证据未在事实手册中找到" : "所需证据未在事实手册中找到");
                computedGaps.add(gap);
                present.add(sectionId);
            }
        }
        if (!computedGaps.isEmpty()) {
            // gaps 缺失/非数组时（模型畸形输出）重建为数组，避免 withArray 抛 UnsupportedOperationException
            JsonNode gaps = blueprint.get("gaps");
            ArrayNode arr = gaps instanceof ArrayNode a ? a : blueprint.putArray("gaps");
            for (ObjectNode g : computedGaps) arr.add(g);
        }
    }

    /** coverage 三态：有效键数==0 → MISSING；0&lt;有效&lt;原 → PARTIAL；全有效且非空 → COVERED。 */
    static String coverageOf(int originalCount, int validCount) {
        if (originalCount <= 0 || validCount <= 0) return "MISSING";
        return validCount < originalCount ? "PARTIAL" : "COVERED";
    }

    /** 确定性质量信号（蓝图为已绑定后的结构）+ task_brief 必要槽位覆盖比例。 */
    static BlueprintQuality computeQuality(JsonNode blueprint, String taskBriefJson) {
        int argumentDensity = 0;
        JsonNode as = blueprint.get("argumentStructure");
        if (as != null && as.isArray()) argumentDensity = as.size();

        int evidenceTotal = 0;
        int covered = 0;
        JsonNode em = blueprint.get("evidenceMap");
        if (em != null && em.isArray()) {
            for (JsonNode e : em) {
                evidenceTotal++;
                if ("COVERED".equals(e.path("coverage").asText(""))) covered++;
            }
        }
        double evidenceCoverage = evidenceTotal == 0 ? 0.0 : (double) covered / evidenceTotal;

        int gapCount = 0;
        JsonNode gaps = blueprint.get("gaps");
        if (gaps != null && gaps.isArray()) gapCount = gaps.size();

        double consistency = taskBriefConsistency(taskBriefJson);
        return new BlueprintQuality(argumentDensity, evidenceCoverage, gapCount, consistency);
    }

    /** task_brief 必要槽位（purpose/audience/mustCover）已填比例（0~1）。 */
    static double taskBriefConsistency(String taskBriefJson) {
        if (taskBriefJson == null || taskBriefJson.isBlank()) return 0.0;
        try {
            JsonNode tb = new ObjectMapper().readTree(taskBriefJson);
            int filled = 0;
            if (slotNonEmpty(tb.get("purpose"))) filled++;
            if (slotNonEmpty(tb.get("audience"))) filled++;
            JsonNode mc = tb.get("mustCover");
            if (mc != null && mc.isArray() && !mc.isEmpty()) filled++;
            return filled / 3.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 质量信号值对象（落 blueprint_quality JSON）。 */
    public record BlueprintQuality(int argumentDensity, double evidenceCoverage, int gapCount,
                                   double taskBriefConsistency) {}

    // ==================== 组装 / 解析辅助 ====================

    /** 组装 user prompt：项目创建输入 + TaskBrief + 研究假设 + 事实手册。 */
    private String buildUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        StringBuilder user = new StringBuilder("主题:").append(p.getTopic() == null ? "" : p.getTopic()).append('\n');
        if (p.getContentDescription() != null && !p.getContentDescription().isBlank()) {
            user.append("内容描述:").append(p.getContentDescription()).append('\n');
        }
        if (p.getAudience() != null && !p.getAudience().isBlank()) {
            user.append("目标读者:").append(p.getAudience()).append('\n');
        }
        user.append("目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        user.append("\n意图契约(TaskBrief JSON):\n").append(b.getTaskBrief()).append('\n');
        String hypotheses = hypothesesBlock(b.getResearchPlan());
        if (hypotheses != null) user.append(hypotheses);
        user.append("\n事实手册(唯一事实来源;evidenceMap.entryKeys 只能逐字引用其中 entries[].key):\n")
                .append(b.getFactSheet());
        return user.toString();
    }

    /** 从 research_plan JSON 提取 hypotheses；缺失/无/畸形 → null（容错跳过，不阻断）。 */
    private String hypothesesBlock(String researchPlan) {
        if (researchPlan == null || researchPlan.isBlank()) return null;
        try {
            JsonNode hs = json.readTree(researchPlan).path("hypotheses");
            if (!hs.isArray() || hs.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("研究假设(请在论证结构中回应其是否被手册证实或推翻):\n");
            boolean any = false;
            for (JsonNode h : hs) {
                String text = h.isTextual() ? h.asText() : h.toString();
                if (text == null || text.isBlank()) continue;
                sb.append("- ").append(text).append('\n');
                any = true;
            }
            return any ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("研究假设注入写作蓝图 prompt 失败,跳过: {}", e.getMessage());
            return null;
        }
    }

    /** fact_sheet entries[].key 白名单（解析失败 → 空集，绑定全部剔除，降级清晰）。 */
    Set<String> factSheetKeys(String factSheet) {
        Set<String> keys = new LinkedHashSet<>();
        if (factSheet == null || factSheet.isBlank()) return keys;
        try {
            JsonNode entries = json.readTree(factSheet).path("entries");
            if (entries.isArray()) {
                for (JsonNode e : entries) {
                    String k = e.path("key").asText("");
                    if (!k.isBlank()) keys.add(k);
                }
            }
        } catch (Exception e) {
            log.warn("事实手册解析失败,证据白名单为空: {}", e.getMessage());
        }
        return keys;
    }

    /** 读字符串数组（非数组/空 → 空列表；元素 null/空白剔除）。 */
    private static List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String s = n.asText();
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static Set<String> gapSectionIds(ObjectNode blueprint) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode gaps = blueprint.get("gaps");
        if (gaps != null && gaps.isArray()) {
            for (JsonNode g : gaps) {
                String sid = g.path("sectionId").asText("");
                if (!sid.isBlank()) ids.add(sid);
            }
        }
        return ids;
    }

    private static ObjectNode qualityNode(BlueprintQuality q) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("argumentDensity", q.argumentDensity());
        n.put("evidenceCoverage", q.evidenceCoverage());
        n.put("gapCount", q.gapCount());
        n.put("taskBriefConsistency", q.taskBriefConsistency());
        return n;
    }

    private static boolean slotNonEmpty(JsonNode slot) {
        if (slot == null || slot.isNull()) return false;
        JsonNode v = slot.get("value");
        if (v == null || v.isNull()) return false;
        if (v.isArray()) return !v.isEmpty();
        String s = v.asText();
        return s != null && !s.isBlank();
    }

    private ArticleBriefEntity requireBrief(Long projectId, Long briefId) {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null || projectId == null || !projectId.equals(b.getProjectId())) {
            throw new IllegalArgumentException("brief 不存在");
        }
        return b;
    }
}
