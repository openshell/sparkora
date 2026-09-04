package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Service
public class ClarifyService {

    private final AiClient aiClient;
    private final ObjectMapper json;

    public ClarifyService(AiClient aiClient, ObjectMapper json) {
        this.aiClient = aiClient;
        this.json = json;
    }

    /**
     * 生成研究计划与澄清问题(①理解 + ②澄清问题派生)。
     * @return 落库后的 brief(id 供后续 run/generate 引用)
     */
    public ArticleBriefEntity clarify(Long projectId, String topic, String extraInfo) {
        String system = """
                你是汽车内容创作的研究规划专家。根据文章主题,产出研究计划与用户澄清问题。
                只输出 JSON 对象:
                {
                  "keyQuestions": ["需要研究的关键问题(3-4 条,每条具体可查)"],
                  "dataNeeds": ["需要的数据(如:价格/尺寸/竞品参数)"],
                  "hypotheses": ["初步假设(可被研究推翻)"],
                  "toolHints": [{"question": "与 keyQuestions 一一对应", "tools": ["KB","WEB"]}],
                  "questions": [
                    {"q": "澄清问题", "type": "input|single", "options": ["single 时的选项"], "required": true}
                  ]
                }
                questions 规则:
                - 3~5 个,只问影响事实与立场的问题(目标读者/对比竞品/立场倾向/期望篇幅)
                - 不问语气风格(风格库职责);type=single 必须给 options;读者/篇幅可默认,竞品对比尽量问
                """;
        StringBuilder user = new StringBuilder("主题:").append(topic).append('\n');
        if (extraInfo != null && !extraInfo.isBlank()) {
            user.append("用户补充:").append(extraInfo).append('\n');
        }
        try {
            AiClient.ChatResult cr = aiClient.chatJson(system, user.toString(), 2048);
            JsonNode node = json.readTree(cr.content());
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("keyQuestions", toArray(node.path("keyQuestions")));
            plan.put("dataNeeds", toArray(node.path("dataNeeds")));
            plan.put("hypotheses", toArray(node.path("hypotheses")));
            plan.put("toolHints", node.path("toolHints"));
            String questions = node.path("questions").toString();

            ArticleBriefEntity b = new ArticleBriefEntity();
            b.setProjectId(projectId);
            b.setGenMode("DEEP");
            b.setResearchPlan(json.writeValueAsString(plan));
            b.setClarifyQuestions(questions);
            b.setCreatedAt(LocalDateTime.now());
            return b;
        } catch (Exception e) {
            throw new IllegalStateException("研究计划生成失败: " + e.getMessage(), e);
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

    private String ansBlank(String s) { return s == null ? "" : s.trim(); }

    private List<String> toArray(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) for (JsonNode n : arr) out.add(n.asText());
        return out;
    }
}