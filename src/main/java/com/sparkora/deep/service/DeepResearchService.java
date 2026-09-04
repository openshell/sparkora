package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.config.DeepProperties;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 深度研究编排(S9 ③④):并行派生子代理研究 → 汇总事实手册 → 落库 research_notes/fact_sheet。
 * 虚拟线程并行;单子代理超时/失败不阻断(缺口进手册);调用次数受 maxAgents 约束。
 */
@Slf4j
@Service
public class DeepResearchService {

    private final ArticleBriefMapper briefMapper;
    private final SubAgentRunner subAgent;
    private final FactSheetService factSheet;
    private final ObjectMapper json;
    private final com.sparkora.config.DeepProperties props;

    public DeepResearchService(ArticleBriefMapper briefMapper, SubAgentRunner subAgent,
                               FactSheetService factSheet, ObjectMapper json,
                               com.sparkora.config.DeepProperties props) {
        this.briefMapper = briefMapper;
        this.subAgent = subAgent;
        this.factSheet = factSheet;
        this.json = json;
        this.props = props;
    }

    /**
     * 执行研究(③④)。同步阻塞至全部子代理完成(前端轮询 status 看逐 agent 进度)。
     * @param briefId 澄清后的 brief id(research_plan/clarify_answers 已落库)
     */
    public Map<String, Object> run(Long briefId) throws Exception {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        JsonNode plan = json.readTree(b.getResearchPlan() == null ? "{}" : b.getResearchPlan());
        List<String> questions = new ArrayList<>();
        List<String> toolHints = new ArrayList<>();
        for (JsonNode q : plan.path("keyQuestions")) questions.add(q.asText());
        // 兼容存量数据:toolHints 可能是 JSON 字符串(历史 bug 产物)或数组
        JsonNode hints = plan.path("toolHints");
        if (hints.isTextual()) {
            try { hints = json.readTree(hints.asText()); } catch (Exception ignored) { }
        }
        for (JsonNode t : hints) toolHints.add(t.path("tools").toString());
        int n = Math.min(questions.size(), props.getMaxAgents());
        if (n == 0) throw new IllegalStateException("研究计划无关键问题");

        // 并行派生(虚拟线程)
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<SubAgentRunner.Note>> futures = new ArrayList<>();
        int webQuotaPerAgent = props.isSearchWebEnabled() ? Math.max(1, 8 / n) : 0;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            String q = questions.get(i);
            List<String> tools = idx < toolHints.size() ? parseTools(toolHints.get(idx)) : List.of("KB");
            futures.add(pool.submit(() -> subAgent.research(q, tools, webQuotaPerAgent)));
        }
        // 收集(超时兜底)
        List<Map<String, Object>> notes = new ArrayList<>();
        int doneCount = 0;
        for (int i = 0; i < futures.size(); i++) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("agentId", i + 1);
            note.put("question", questions.get(i));
            try {
                SubAgentRunner.Note r = futures.get(i).get(props.getResearchTimeoutMs(), TimeUnit.MILLISECONDS);
                note.put("status", r.status());
                note.put("factsJson", r.factsJson());
                note.put("webCount", r.webCount());
                doneCount++;
            } catch (Exception e) {
                note.put("status", "FAILED");
                note.put("factsJson", "{\"facts\":[],\"gaps\":[\"子代理超时或失败:" + e.getMessage() + "\"]}");
                note.put("webCount", 0);
            }
            notes.add(note);
        }
        pool.shutdown();
        String notesJson = json.writeValueAsString(notes);
        b.setResearchNotes(notesJson);
        // ④ 汇总事实手册
        b.setFactSheet(factSheet.merge(notesJson));
        briefMapper.updateById(b);
        log.info("深度研究完成 briefId={} agents={} done={} webCalls={} notesLen={}",
                briefId, futures.size(), doneCount,
                notes.stream().mapToInt(m -> (Integer) m.get("webCount")).sum(), notesJson.length());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", briefId);
        out.put("agents", futures.size());
        out.put("done", doneCount);
        return out;
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
}