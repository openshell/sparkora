package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.common.R;
import com.sparkora.deep.service.ClarifyService;
import com.sparkora.deep.service.DeepResearchService;
import com.sparkora.deep.service.DeepWriterService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.security.SecurityUtil;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 深度生成模式接口(S9):
 * POST /deep/clarify           ①② 研究计划+澄清问题生成(落 brief,gen_mode=DEEP)
 * POST /deep/clarify-answer    锁定用户答案
 * POST /deep/run               ③④ 并行研究+事实手册(同步阻塞,前端轮询 /deep/status)
 * POST /deep/generate          ⑤⑥ 深度写作+数值回查(落 version)
 * GET  /deep/status            断点/进度查询(研究计划/逐 agent 状态/手册摘要)
 */
@RestController
@RequestMapping("/api/projects/{projectId}/deep")
public class DeepController {

    private final ClarifyService clarifyService;
    private final DeepResearchService researchService;
    private final DeepWriterService writerService;
    private final ArticleBriefMapper briefMapper;
    private final com.sparkora.deep.tool.SearxngSearchTool searxngTool;
    private final com.sparkora.deep.tool.TavilySearchTool tavilyTool;
    private final com.sparkora.config.DeepProperties deepProps;

    public DeepController(ClarifyService clarifyService, DeepResearchService researchService,
                          DeepWriterService writerService, ArticleBriefMapper briefMapper,
                          com.sparkora.deep.tool.SearxngSearchTool searxngTool,
                          com.sparkora.deep.tool.TavilySearchTool tavilyTool,
                          com.sparkora.config.DeepProperties deepProps) {
        this.clarifyService = clarifyService;
        this.researchService = researchService;
        this.writerService = writerService;
        this.briefMapper = briefMapper;
        this.searxngTool = searxngTool;
        this.tavilyTool = tavilyTool;
        this.deepProps = deepProps;
    }

    /** ①② 研究计划+澄清问题。body: {extraInfo?}。 */
    @PostMapping("/clarify")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> clarify(@PathVariable Long projectId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            String topic = body == null ? null : (String) body.get("topic");
            String extraInfo = body == null ? null : (String) body.get("extraInfo");
            if (topic == null || topic.isBlank()) {
                // 主题从项目取:调用方保证项目存在(简化:由前端先传 topic,或查项目)
                return R.fail(400, "缺少 topic");
            }
            ArticleBriefEntity b = clarifyService.clarify(projectId, topic, extraInfo);
            briefMapper.insert(b);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("briefId", b.getId());
            out.put("researchPlan", b.getResearchPlan());
            out.put("questions", b.getClarifyQuestions());
            return R.ok(out);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "研究计划生成失败: " + e.getMessage());
        }
    }

    /** 锁定澄清答案。body: {briefId, answers: {问题:答案}}。 */
    @PostMapping("/clarify-answer")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> clarifyAnswer(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            ArticleBriefEntity b = briefMapper.selectById(briefId);
            if (b == null || !projectId.equals(b.getProjectId())) return R.fail(404, "brief 不存在");
            String locked = clarifyService.lockAnswers(b.getClarifyQuestions(),
                    jsonOf(body.get("answers")));
            b.setClarifyAnswers(locked);
            briefMapper.updateById(b);
            return R.ok(Map.of("briefId", briefId, "locked", locked));
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** ③④ 并行研究+事实手册(同步,耗时 = 子代理数 × 单代理时长)。body: {briefId}。 */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> run(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            return R.ok(researchService.run(briefId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "研究执行失败: " + e.getMessage());
        }
    }

    /** ⑤⑥ 深度写作+数值回查。body: {briefId, stylePrompt?}。 */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> generate(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            String stylePrompt = body.get("stylePrompt") == null ? "" : String.valueOf(body.get("stylePrompt"));
            Long versionId = writerService.write(projectId, briefId, stylePrompt);
            return R.ok(Map.of("versionId", versionId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "深度写作失败: " + e.getMessage());
        }
    }

    /** 进度/产物查询(前端轮询;含逐 agent 状态与手册摘要)。 */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public R<Map<String, Object>> status(@PathVariable Long projectId,
                                         @RequestParam(required = false) Long briefId) {
        try {
            ArticleBriefEntity b = briefId != null ? briefMapper.selectById(briefId)
                    : briefMapper.selectOne(new QueryWrapper<ArticleBriefEntity>()
                        .eq("project_id", projectId).eq("gen_mode", "DEEP")
                        .orderByDesc("id").last("LIMIT 1"));
            if (b == null) return R.ok(Map.of("stage", "NONE"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("briefId", b.getId());
            out.put("genMode", b.getGenMode());
            out.put("stage", stageOf(b));
            // 工具健康(设计 6.3):KB 恒可用;SEARXNG/TAVILY 取惰性状态(最近一次调用结果)
            Map<String, Boolean> toolHealth = new LinkedHashMap<>();
            toolHealth.put("KB", true);
            toolHealth.put("SEARXNG", deepProps.isSearchWebEnabled() && searxngTool.available());
            toolHealth.put("TAVILY", deepProps.isSearchWebEnabled() && tavilyTool.available());
            out.put("toolHealth", toolHealth);
            if (b.getResearchPlan() != null) out.put("researchPlan", b.getResearchPlan());
            if (b.getClarifyQuestions() != null) out.put("questions", b.getClarifyQuestions());
            if (b.getClarifyAnswers() != null) out.put("answers", b.getClarifyAnswers());
            if (b.getResearchNotes() != null) out.put("agents", b.getResearchNotes());
            if (b.getFactSheet() != null) out.put("factSheet", b.getFactSheet());
            return R.ok(out);
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    private String stageOf(ArticleBriefEntity b) {
        if (b.getFactSheet() != null && !b.getFactSheet().isBlank()) return "RESEARCH_DONE";
        if (b.getResearchNotes() != null && !b.getResearchNotes().isBlank()) return "RESEARCHING";
        if (b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank()) return "CLARIFIED";
        if (b.getClarifyQuestions() != null && !b.getClarifyQuestions().isBlank()) return "CLARIFYING";
        return "NONE";
    }

    private String jsonOf(Object o) {
        if (o == null) return "{}";
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }
}