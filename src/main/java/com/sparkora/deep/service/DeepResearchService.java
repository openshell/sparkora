package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 深度研究编排(S9 ③④):并行派生子代理研究 → 汇总事实手册 → 落库 research_notes/fact_sheet。
 *
 * 异步 + 逐 agent 落库(设计 6.1/6.3):
 *  - run() 同步校验 + 落全 PENDING 占位 research_notes → 立即返回(202 语义),后台 self.runAsync 执行;
 *  - runAsync() @Async 逐 agent 执行,每个完成时把 research_notes 对应条目更新为 RUNNING→DONE/FAILED,
 *    前端轮询 /deep/status 即可看到逐 agent 进度(而非一次性全量)。
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
                               com.sparkora.mapper.ArticleProjectMapper projectMapper) {
        this.briefMapper = briefMapper;
        this.subAgent = subAgent;
        this.factSheet = factSheet;
        this.json = json;
        this.props = props;
        this.briefService = briefService;
        this.carService = carService;
        this.matcherService = matcherService;
        this.projectMapper = projectMapper;
    }

    /**
     * 启动研究(同步校验 + 落 PENDING 占位,立即返回;后台异步执行)。
     * @return {briefId, agents, started:true}
     */
    public Map<String, Object> run(Long briefId) throws Exception {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        JsonNode plan = json.readTree(b.getResearchPlan() == null ? "{}" : b.getResearchPlan());
        List<String> questions = new ArrayList<>();
        for (JsonNode q : plan.path("keyQuestions")) questions.add(q.asText());
        int n = Math.min(questions.size(), props.getMaxAgents());
        if (n == 0) throw new IllegalStateException("研究计划无关键问题");

        // 落全 PENDING 占位(前端轮询立即可见 agent 列表)
        List<Map<String, Object>> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("agentId", i + 1);
            note.put("question", questions.get(i));
            note.put("status", "PENDING");
            note.put("factsJson", "{\"facts\":[],\"gaps\":[]}");
            note.put("webCount", 0);
            pending.add(note);
        }
        b.setResearchNotes(json.writeValueAsString(pending));
        briefMapper.updateById(b);

        // 后台异步执行(逐 agent 落库)
        self.runAsync(briefId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", briefId);
        out.put("agents", n);
        out.put("started", true);
        return out;
    }

    /** 异步执行研究(逐 agent 落库;由 self 代理调用)。 */
    @Async
    public void runAsync(Long briefId) {
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
            int n = Math.min(questions.size(), props.getMaxAgents());
            if (n == 0) return;

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<SubAgentRunner.Note>> futures = new ArrayList<>();
            int webQuotaPerAgent = props.isSearchWebEnabled() ? Math.max(1, 8 / n) : 0;
            // R1 锚点车型解析:项目关联为准;为空时按主题识别兜底(失败不阻断研究)
            ArticleBriefEntity b0 = briefMapper.selectById(briefId);
            Long projectId = b0 == null ? null : b0.getProjectId();
            List<Long> anchors = projectId == null ? List.of() : resolveAnchors(projectId);
            String topic = projectId == null ? "" : resolveTopic(projectId);
            log.info("深度研究锚点解析 projectId={} anchors={} topic={}", projectId, anchors, topic);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                String q = questions.get(i);
                List<String> tools = idx < toolHints.size() ? parseTools(toolHints.get(idx)) : List.of("KB");
                futures.add(pool.submit(() -> subAgent.research(q, tools, webQuotaPerAgent, anchors, topic)));
            }
            int doneCount = 0;
            int webTotal = 0;
            for (int i = 0; i < futures.size(); i++) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("agentId", i + 1);
                note.put("question", questions.get(i));
                // 置 RUNNING(逐 agent 可见)
                note.put("status", "RUNNING");
                note.put("factsJson", "{\"facts\":[],\"gaps\":[]}");
                note.put("webCount", 0);
                updateAgent(briefId, i + 1, note);
                try {
                    SubAgentRunner.Note r = futures.get(i).get(props.getResearchTimeoutMs(), TimeUnit.MILLISECONDS);
                    note.put("status", r.status());
                    note.put("factsJson", r.factsJson());
                    note.put("webCount", r.webCount());
                    doneCount++;
                    webTotal += r.webCount();
                } catch (Exception e) {
                    note.put("status", "FAILED");
                    note.put("factsJson", "{\"facts\":[],\"gaps\":[\"子代理超时或失败:" + e.getMessage() + "\"]}");
                    note.put("webCount", 0);
                }
                updateAgent(briefId, i + 1, note);
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
            log.info("深度研究完成 briefId={} agents={} done={} webCalls={}",
                    briefId, futures.size(), doneCount, webTotal);
        } catch (Exception e) {
            log.error("深度研究异步执行失败 briefId={}: {}", briefId, e.getMessage(), e);
        }
    }

    /** 更新 research_notes 中指定 agentId 的条目(读改写,幂等)。 */
    private void updateAgent(Long briefId, int agentId, Map<String, Object> note) {
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
                if (n.path("agentId").asInt() == agentId) {
                    m.put("status", note.get("status"));
                    m.put("factsJson", note.get("factsJson"));
                    m.put("webCount", note.get("webCount"));
                }
                notes.add(m);
            }
            b.setResearchNotes(json.writeValueAsString(notes));
            briefMapper.updateById(b);
        } catch (Exception e) {
            log.warn("更新 agent 状态失败 briefId={} agentId={}: {}", briefId, agentId, e.getMessage());
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

    /** R1 锚点车型解析:项目关联车型为准;为空时主题识别兜底(与 create 建项同一 matcher,失败不阻断研究)。 */
    private List<Long> resolveAnchors(Long projectId) {
        try {
            List<Long> ids = carService.listModelIds(projectId);
            if (ids != null && !ids.isEmpty()) return ids;
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            if (p == null) return List.of();
            com.sparkora.car.service.CarModelMatcherService.MatchResult m = matcherService.match(p.getTopic(), p.getKeywords());
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
}