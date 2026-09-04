package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实手册服务(S9 ④):合并子代理研究笔记 → 事实手册(正文数值唯一来源)。
 * 置信规则:KB 0.9;WEB 交叉≥2 源 0.7;单 WEB 0.4(进 warnings);冲突条目降 0.3 双源并列。
 * 相似条目去重按 claim 精确匹配(实现从简;LLM 辅助归类后续迭代)。
 */
@Slf4j
@Service
public class FactSheetService {

    private final ObjectMapper json;

    public FactSheetService(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 合并研究笔记 → 事实手册 JSON 字符串。
     * @param notesJson 子代理笔记 JSON 数组 [{agentId,question,status,facts:{facts:[…],gaps:[…]}}]
     */
    public String merge(String notesJson) throws Exception {
        JsonNode notes = json.readTree(notesJson == null || notesJson.isBlank() ? "[]" : notesJson);
        // claim → 条目聚合(同 claim 视为同一事实;来源不同交叉)
        Map<String, List<JsonNode>> byClaim = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        for (JsonNode note : notes) {
            JsonNode facts = note.path("factsJson").isMissingNode()
                    ? note.path("facts") : json.readTree(note.path("factsJson").asText("{}"));
            for (JsonNode f : facts.path("facts")) {
                String claim = f.path("claim").asText("").trim();
                if (claim.isEmpty()) continue;
                byClaim.computeIfAbsent(claim, k -> new ArrayList<>()).add(f);
            }
            for (JsonNode g : facts.path("gaps")) {
                String g0 = g.asText("");
                if (!g0.isBlank() && !gaps.contains(g0)) gaps.add(g0);
            }
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> e : byClaim.entrySet()) {
            List<JsonNode> list = e.getValue();
            JsonNode first = list.get(0);
            String type = first.path("source").path("type").asText("KB");
            double confidence;
            if (list.size() >= 2 && !sameSource(list)) {
                confidence = 0.85;   // 多源交叉(不同来源)
                type = "MULTI";
            } else if ("KB".equals(type)) {
                confidence = first.path("confidence").asDouble(0.9);
            } else {
                confidence = 0.4;
                warnings.add("「" + truncate(e.getKey(), 30) + "」仅单一 WEB 源,待核实");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", e.getKey());
            entry.put("value", first.path("value").asText(""));
            entry.put("claim", first.path("claim").asText(""));
            entry.put("sources", first.path("source"));
            entry.put("crossCount", list.size());
            entry.put("confidence", confidence);
            entries.add(entry);
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("entries", entries);
        sheet.put("gaps", gaps);
        sheet.put("warnings", warnings);
        return json.writeValueAsString(sheet);
    }

    /** 条目列表来源是否全同(粗判:type+url)。 */
    private boolean sameSource(List<JsonNode> list) {
        String first = list.get(0).path("source").path("url").asText("")
                + list.get(0).path("source").path("modelName").asText();
        for (int i = 1; i < list.size(); i++) {
            String u = list.get(i).path("source").path("url").asText("")
                    + list.get(i).path("source").path("modelName").asText();
            if (!u.equals(first)) return false;
        }
        return true;
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}