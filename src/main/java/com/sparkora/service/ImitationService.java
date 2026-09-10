package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.StyleProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章仿写服务(09-09-article-imitation)。
 *
 * analyze:对参考原文做一次 AI 分析(题材/结构骨架/句式特征 → brief,gen_mode=IMITATION)
 *   并推荐风格库中 ≤3 个 enabled 风格(附理由 → brief.styleRecommendations)。
 *   状态守护与原子抢占完全仿 BriefService(DRAFT/READY 放行,失败回 DRAFT 写 last_brief_error)。
 *   仿写不做 RAG 检索(任意题材原文与车型库强行匹配会注入无关数据约束,污染仿写)。
 *
 * similarityCheck:纯本地相似度自检(不调 AI):
 *   1. 规范化(去 Markdown 标记/空白/标点,保留中英文与数字)
 *   2. 字符 5-gram 重合率:|仿写 n-gram ∩ 原文 n-gram| / |仿写 n-gram|(防照搬视角)
 *   3. 最长公共连续片段(朴素 DP)+ 连续 ≥10 字重复片段列表
 *   阈值常量:SIM_WARN=0.40 / SIM_HIGH=0.60 / RUN_WARN=13(实验性调参,YAGNI 不进 .env)。
 */
@Slf4j
@Service
public class ImitationService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleBriefMapper briefMapper;
    private final StyleProfileMapper styleMapper;
    private final AiClient aiClient;
    private final ObjectMapper json;

    /** 相似度警示阈值:0.40~0.60 黄色提示,≥0.60 红色警示。 */
    public static final double SIM_WARN = 0.40;
    public static final double SIM_HIGH = 0.60;
    /** 最长重复片段超此长度(字符)时在报告中标高。 */
    public static final int RUN_WARN = 13;

    /** 生成中状态超过该时长视为陈旧(JVM 中途死亡/重启残留),允许重新触发以自愈。 */
    private static final long STALE_GENERATING_MS = 10 * 60 * 1000L;

    /** 分析送 AI 的原文截断上限(全文仍入库;沿用 StyleService.extract 先例)。 */
    private static final int ANALYZE_TEXT_LIMIT = 8000;

    public ImitationService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                            StyleProfileMapper styleMapper, AiClient aiClient, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.styleMapper = styleMapper;
        this.aiClient = aiClient;
        this.json = json;
    }

    /**
     * 分析原文 + 风格推荐(一次 AI 调用产出双结果)。
     * 状态机:DRAFT/READY → GENERATING_BRIEF → READY;失败回 DRAFT 写 last_brief_error。
     * @return 新生成的 brief(含分析与 styleRecommendations)
     */
    public ArticleBriefEntity analyze(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (!"IMITATION".equals(p.getGenSource()))
            throw new IllegalArgumentException("仅文章仿写项目可分析原文");
        if (p.getImitationText() == null || p.getImitationText().isBlank())
            throw new IllegalArgumentException("项目缺少参考原文,无法分析");
        // 并发防护:正在生成中(未过期)拒绝重复触发;陈旧状态放行自愈(与 BriefService 相同)
        String s = p.getStatus();
        boolean generating = "GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s);
        if (generating && p.getUpdatedAt() != null
                && p.getUpdatedAt().isAfter(LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS)))) {
            throw new IllegalStateException("该项目正在生成中,请稍候(刷新页面可查看进度)");
        }
        // 原子抢占置 GENERATING_BRIEF:仅 DRAFT/READY 或陈旧生成中可成功(仿 BriefService.claimGenerating)
        LocalDateTime staleCutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "DRAFT", "READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_BRIEF")
                .set("last_brief_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(BriefService.projectStatusGuardMsg(p, "分析原文"));
        p.setStatus("GENERATING_BRIEF");

        try {
            // enabled 风格列表喂给 AI 做推荐(风格库为空则推荐为空数组,前端引导去风格库,不阻断)
            List<StyleProfileEntity> enabledStyles = styleMapper.selectList(
                    new QueryWrapper<StyleProfileEntity>().eq("enabled", true).orderByDesc("id"));
            StringBuilder styleCtx = new StringBuilder();
            for (StyleProfileEntity st : enabledStyles) {
                styleCtx.append("- id=").append(st.getId())
                        .append(" 名:").append(st.getName())
                        .append(" 描述:").append(nv(st.getDescription())).append('\n');
            }

            String analyzeText = truncate(p.getImitationText(), ANALYZE_TEXT_LIMIT);
            String system = buildAnalyzeSystemPrompt();
            String user = "【参考原文】\n" + analyzeText + "\n\n【候选风格库(仅可从此列表中推荐,不得编造库外风格)】\n"
                    + (styleCtx.isEmpty() ? "(风格库暂无启用风格,recommendations 返回空数组)" : styleCtx);

            AiClient.ChatResult cr = aiClient.chatJson(system, user, 2048);
            // AI 输出 JSON 容错:剥围栏+转义字符串内裸控制字符(同 VersionService)
            JsonNode node = json.readTree(AiClient.sanitizeAiJson(cr.content()));

            // 落 brief:分析结果复用 outline/coreViewpoints/titleCandidates(语义:结构骨架/核心观点/标题候选)
            ArticleBriefEntity b = new ArticleBriefEntity();
            b.setProjectId(projectId);
            b.setGenMode("IMITATION");
            b.setTitleCandidates(node.path("titleCandidates").toString());
            b.setCoreViewpoints(node.path("coreViewpoints").toString());
            b.setOutline(node.path("outline").toString());
            // 风格推荐:截断到 ≤3 条,防御 AI 超发;只保留库内存在的 styleId
            b.setStyleRecommendations(recommendationsJson(node.path("recommendations"), enabledStyles));
            b.setAiModel(cr.model());
            b.setTokenUsage(cr.totalTokens());
            b.setRagStatus("NO_KNOWLEDGE"); // 仿写不做 RAG(设计决策:车型库与任意题材强行匹配会污染仿写)
            b.setCreatedAt(LocalDateTime.now());
            briefMapper.insert(b);

            // 分析结果冗余存 project(前端 GET /imitation 直读,免查 brief)
            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("genre", node.path("genre").asText(""));
            analysis.put("structure", node.path("structure").asText(""));
            analysis.put("sentenceFeatures", node.path("sentenceFeatures").asText(""));
            p.setCurrentBriefId(b.getId());
            p.setImitationAnalysis(json.writeValueAsString(analysis));
            p.setStatus("READY");
            p.setLastBriefError(null);
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            return b;
        } catch (Exception e) {
            // 失败回 DRAFT 并记录原因(截断防超列,同 BriefService);项目已被删除时跳过回退,保留原始异常
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            log.warn("原文分析失败 project={}: {}", projectId, reason, e);
            ArticleProjectEntity fresh = projectMapper.selectById(projectId);
            if (fresh != null) {
                fresh.setStatus("DRAFT");
                fresh.setLastBriefError(reason);
                fresh.setUpdatedAt(LocalDateTime.now());
                projectMapper.updateById(fresh);
            }
            if (e instanceof AiException ae) throw ae;
            throw new AiException("原文分析失败: " + reason, e);
        }
    }

    /** 取项目最近一次仿写分析(brief + 冗余 analysis),无则 null。 */
    public Map<String, Object> currentAnalysis(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = p.getCurrentBriefId() == null ? null : briefMapper.selectById(p.getCurrentBriefId());
        if (b == null || !"IMITATION".equals(b.getGenMode())) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("briefId", b.getId());
        out.put("titleCandidates", b.getTitleCandidates());
        out.put("coreViewpoints", b.getCoreViewpoints());
        out.put("outline", b.getOutline());
        out.put("styleRecommendations", b.getStyleRecommendations());
        try {
            out.put("analysis", p.getImitationAnalysis() == null ? null : json.readTree(p.getImitationAnalysis()));
        } catch (Exception e) {
            out.put("analysis", null);
        }
        return out;
    }

    /**
     * 相似度自检(纯本地):5-gram 重合率 + 最长公共连续片段 + ≥10 字重复片段列表。
     * @return Map{score, report}(report 为 JSON 字符串,落 version.similarity_report)
     */
    public Map<String, Object> similarityCheck(String originalText, String contentMd) {
        String a = normalize(originalText);
        String b = normalize(contentMd);
        Map<String, Object> out = new LinkedHashMap<>();
        double score = ngramScore(b, a);
        out.put("score", round4(score));
        int[] dp = longestCommonRun(a, b);
        int maxRun = dp[0];
        int endB = dp[1];
        List<Map<String, Object>> runs = repeatedRuns(b, a);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("maxRunLength", maxRun);
        if (maxRun >= RUN_WARN && endB >= 0) {
            report.put("maxRunText", truncate(b.substring(Math.max(0, endB - maxRun + 1), endB + 1), 120));
        }
        report.put("repeatedRuns", runs);
        report.put("thresholds", Map.of("warn", SIM_WARN, "high", SIM_HIGH));
        String reportJson;
        try {
            reportJson = json.writeValueAsString(report);
        } catch (Exception e) {
            reportJson = null;
        }
        out.put("report", reportJson);
        return out;
    }

    // ==================== analyze 内部 ====================

    private String buildAnalyzeSystemPrompt() {
        return """
                你是新媒体内容分析专家。阅读参考原文,产出「原文分析」与「风格推荐」两部分结果。
                只输出 JSON 对象,字段如下,不要任何额外文字:
                {
                  "genre": "题材一句话(如:科技产品评测/个人成长随笔/行业观察)",
                  "structure": "结构骨架一句话(如:痛点开场→三层递进论证→行动号召收尾)",
                  "sentenceFeatures": "句式特征一句话(如:短句为主,多用反问与排比,口语化)",
                  "titleCandidates": ["从原文主题提炼的 3 个标题候选"],
                  "coreViewpoints": ["原文的核心观点 2-4 条(保留观点组织,供仿写参考)"],
                  "outline": [{"heading":"章节标题(反映原文结构)","subPoints":["2-4 个要点"]}],
                  "recommendations": [
                    {"styleId": 数字, "name": "风格名", "reason": "为何适配这篇原文的仿写(1-2 句)", "matchScore": 0 到 1 的小数}
                  ]
                }
                recommendations 规则:
                - 仅从候选风格库中选,最多 3 个,按匹配度从高到低;风格库为空时给空数组
                - 依据原文的题材/语气/结构与候选风格的描述匹配,理由要具体到「为什么适合仿写这篇」
                - 所有内容用中文。
                """;
    }

    /** 防御:只保留库内 styleId、截断 ≤3 条;解析失败返回 "[]"。 */
    private String recommendationsJson(JsonNode arr, List<StyleProfileEntity> enabledStyles) {
        try {
            Set<Long> validIds = new HashSet<>();
            for (StyleProfileEntity st : enabledStyles) validIds.add(st.getId());
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    if (out.size() >= 3) break;
                    long id = n.path("styleId").asLong(-1);
                    if (!validIds.contains(id)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("styleId", id);
                    m.put("name", n.path("name").asText(""));
                    m.put("reason", n.path("reason").asText(""));
                    m.put("matchScore", round4(n.path("matchScore").asDouble(0)));
                    out.add(m);
                }
            }
            return json.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("风格推荐解析失败,置空数组: {}", e.getMessage());
            return "[]";
        }
    }

    // ==================== 相似度计算(纯本地) ====================

    /** 规范化:去 Markdown 标记/HTML img/空白/标点,仅保留中英文与数字(小写化)。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("(?s)!\\[[^\\]]*\\]\\([^)]*\\)", "");   // Markdown 图片
        s = s.replaceAll("(?s)<img[^>]*>", "");                   // HTML img
        s = s.replaceAll("(?s)\\[[^\\]]*\\]\\([^)]*\\)", "");     // Markdown 链接(留文字)
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");                  // 标题井号
        s = s.replaceAll("[*_~`>#|\\[\\]]", "");                  // 强调/表格等标记
        s = s.replaceAll("[\\p{Punct}\\s\\p{IsPunctuation}]+", ""); // 全部标点与空白
        return s.toLowerCase();
    }

    /** 5-gram 重合率:|仿写 n-gram ∩ 原文 n-gram| / |仿写 n-gram|(防照搬视角:仿写中有多少来自原文)。 */
    static double ngramScore(String imitationNorm, String originalNorm) {
        final int N = 5;
        if (imitationNorm.length() < N) return 0.0;
        Set<String> origSet = new HashSet<>();
        for (int i = 0; i + N <= originalNorm.length(); i++) origSet.add(originalNorm.substring(i, i + N));
        if (origSet.isEmpty()) return 0.0;
        Set<String> imiSet = new HashSet<>();
        for (int i = 0; i + N <= imitationNorm.length(); i++) imiSet.add(imitationNorm.substring(i, i + N));
        if (imiSet.isEmpty()) return 0.0;
        int hit = 0;
        for (String g : imiSet) if (origSet.contains(g)) hit++;
        return (double) hit / imiSet.size();
    }

    /** 最长公共连续片段长度(朴素 DP,a 作行);返回 {长度, b 中结束下标(含,-1 无)}。 */
    static int[] longestCommonRun(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return new int[]{0, -1};
        int best = 0, endB = -1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = ca == b.charAt(j - 1) ? prev[j - 1] + 1 : 0;
                if (cur[j] > best) {
                    best = cur[j];
                    endB = j - 1;
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return new int[]{best, endB};
    }

    /** 连续 ≥10 字且在原文中出现的重复片段(去重,按长度降序,最多 10 条,单条截 120 字)。 */
    static List<Map<String, Object>> repeatedRuns(String imitationNorm, String originalNorm) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (imitationNorm.length() < 10) return out;
        Set<String> seen = new HashSet<>();
        int i = 0;
        while (i + 10 <= imitationNorm.length() && out.size() < 10) {
            // 贪心扩展:从 i 起找与原文公共的最长片段
            int len = 0;
            int j = i;
            while (j < imitationNorm.length()) {
                String cand = imitationNorm.substring(i, j + 1);
                if (originalNorm.contains(cand)) {
                    len = j - i + 1;
                    j++;
                } else break;
            }
            if (len >= 10) {
                String text = imitationNorm.substring(i, i + len);
                if (seen.add(text)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("text", truncate(text, 120));
                    m.put("length", len);
                    out.add(m);
                }
                i += len; // 跳过已覆盖片段,避免碎片化
            } else {
                i++;
            }
        }
        // 按长度降序
        out.sort((x, y) -> Integer.compare((int) y.get("length"), (int) x.get("length")));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "(无)" : s; }
}