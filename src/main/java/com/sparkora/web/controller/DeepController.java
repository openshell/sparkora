package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.common.R;
import com.sparkora.deep.service.ClarifyService;
import com.sparkora.deep.service.DeepResearchService;
import com.sparkora.deep.service.DeepWriterService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 深度生成模式接口(S9):
 * POST /deep/clarify           ①② 研究计划+澄清问题生成(落 brief,gen_mode=DEEP)
 * POST /deep/clarify-answer    锁定用户答案
 * POST /deep/run               ③④ 并行研究+事实手册(同步阻塞,前端轮询 /deep/status)
 * POST /deep/generate          ⑤⑥ 深度写作+数值回查(批量异步:落版本,前端轮询状态翻转)
 * GET  /deep/status            断点/进度查询(研究计划/逐 agent 状态/手册摘要)
 */
@RestController
@RequestMapping("/api/projects/{projectId}/deep")
public class DeepController {

    private final ClarifyService clarifyService;
    private final DeepResearchService researchService;
    private final DeepWriterService writerService;
    private final ArticleBriefMapper briefMapper;
    /** 深度简报生成(手动重试 /deep/brief) */
    private final com.sparkora.service.BriefService briefService;
    private final com.sparkora.deep.tool.SearxngSearchTool searxngTool;
    private final com.sparkora.deep.tool.TavilySearchTool tavilyTool;
    private final com.sparkora.config.DeepProperties deepProps;
    /** 系统检索设置(09-15:toolHealth 反映真实 KB/WEB 运行时门控) */
    private final com.sparkora.service.SettingService settingService;

    public DeepController(ClarifyService clarifyService, DeepResearchService researchService,
                          DeepWriterService writerService, ArticleBriefMapper briefMapper,
                          com.sparkora.service.BriefService briefService,
                          com.sparkora.deep.tool.SearxngSearchTool searxngTool,
                          com.sparkora.deep.tool.TavilySearchTool tavilyTool,
                          com.sparkora.config.DeepProperties deepProps,
                          com.sparkora.service.SettingService settingService) {
        this.clarifyService = clarifyService;
        this.researchService = researchService;
        this.writerService = writerService;
        this.briefMapper = briefMapper;
        this.briefService = briefService;
        this.searxngTool = searxngTool;
        this.tavilyTool = tavilyTool;
        this.deepProps = deepProps;
        this.settingService = settingService;
    }

    /** ①② 研究计划+澄清问题(09-11 异步:落 PLANNING 占位立即返回,前端轮询 /deep/status)。body: {topic?, extraInfo?}。 */
    @PostMapping("/clarify")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> clarify(@PathVariable Long projectId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            String topic = body == null ? null : (String) body.get("topic");
            String extraInfo = body == null ? null : (String) body.get("extraInfo");
            ArticleBriefEntity b = clarifyService.start(projectId, topic, extraInfo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("briefId", b.getId());
            out.put("stage", "PLANNING");
            return R.ok(out);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 并发/陈旧冲突:同一项目已有 PLANNING 占位(部分唯一索引兜底)
            return R.fail(409, e.getMessage());
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

    /** ③④ 并行研究+事实手册(异步,立即返回;前端轮询 /deep/status)。body: {briefId}。 */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> run(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            return R.ok(researchService.run(projectId, briefId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 运行互斥/计划未就绪:409
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "研究执行失败: " + e.getMessage());
        }
    }

    /**
     * ⑤⑥ 深度写作+数值回查(09-27-gen-async 批量异步化):同步毫秒级返回占位标记,后台 @Async 逐风格生成;
     * 前端靠项目状态轮询(GENERATING_VERSIONS→VERSIONS_READY)翻转刷新。
     * body: {briefId, styleIds:[...]}(批量,首选);兼容单 styleId(数组化)与旧 stylePrompt/styleName(deprecated)。
     */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<Map<String, Object>> generate(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            List<Long> styleIds = parseStyleIds(body);
            if (!styleIds.isEmpty()) {
                return R.ok(writerService.startBatch(projectId, briefId, styleIds));
            }
            // deprecated:兼容旧前端(直接传风格画像字符串);新前端恒传 styleIds[]
            String stylePrompt = body.get("stylePrompt") == null ? "" : String.valueOf(body.get("stylePrompt"));
            String styleName = body.get("styleName") == null ? "" : String.valueOf(body.get("styleName"));
            return R.ok(writerService.startBatchLegacy(projectId, briefId, stylePrompt, styleName));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, "深度写作失败: " + e.getMessage());
        }
    }

    /** 解析批量风格 id:styleIds[] 优先;兼容单 styleId(包装为单元素列表);两者均缺省返回空列表。 */
    private static List<Long> parseStyleIds(Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object arr = body.get("styleIds");
        if (arr instanceof List<?> list) {
            for (Object o : list) {
                if (o == null || String.valueOf(o).isBlank()) continue;
                ids.add(Long.valueOf(String.valueOf(o)));
            }
        } else if (arr != null && !String.valueOf(arr).isBlank()) {
            ids.add(Long.valueOf(String.valueOf(arr)));
        }
        if (ids.isEmpty()) {
            Object single = body.get("styleId");
            if (single != null && !String.valueOf(single).isBlank()) {
                ids.add(Long.valueOf(String.valueOf(single)));
            }
        }
        return ids;
    }

    /** 基于事实手册生成简报(手动重试入口;研究完成后后端也会自动触发一次)。body: {briefId}。 */
    @PostMapping("/brief")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public R<ArticleBriefEntity> deepBrief(@PathVariable Long projectId, @RequestBody Map<String, Object> body) {
        try {
            Long briefId = Long.valueOf(String.valueOf(body.get("briefId")));
            return R.ok(briefService.generateFromFactSheet(projectId, briefId));
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(409, e.getMessage());
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
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
            out.put("planStatus", b.getPlanStatus());
            // 工具健康(09-15):状态码 OK|DISABLED|UNCONFIGURED|FAILED,如实反映设置门控与配置态
            boolean webAllowed = deepProps.isSearchWebEnabled() && settingService.isWebSearchEnabled();
            Map<String, String> toolHealth = new LinkedHashMap<>();
            toolHealth.put("KB", settingService.isKbEnabled() ? "OK" : "DISABLED");
            toolHealth.put("SEARXNG", webHealth(webAllowed, searxngTool.configured(), searxngTool.lastCallOk()));
            toolHealth.put("TAVILY", webHealth(webAllowed, tavilyTool.configured(), tavilyTool.lastCallOk()));
            out.put("toolHealth", toolHealth);
            // 外部搜索策略(09-25):增量暴露有效策略(运行时设置优先 > 部署级默认),不改既有三键值域
            var snap = researchService.resolveSnapshot(b.getId());
            out.put("webStrategy", snap.strategyLabel());
            out.put("webProviderOrder", snap.strategyRaw());
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

    /** WEB 工具健康状态码:门控关闭 > 未配置 > 最近调用失败 > 正常。 */
    private static String webHealth(boolean allowed, boolean configured, boolean lastCallOk) {
        if (!allowed) return "DISABLED";
        if (!configured) return "UNCONFIGURED";
        return lastCallOk ? "OK" : "FAILED";
    }

    private String stageOf(ArticleBriefEntity b) {
        // 09-11:研究计划异步生成中(clarify 占位行)优先暴露,先于 questions 判定
        if ("PLANNING".equals(b.getPlanStatus())) return "PLANNING";
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