package com.sparkora.deep.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深度写作与数值校验(S9 ⑤⑥):
 * ⑤ 写作:风格画像 + 事实手册 + 锁定需求 → 正文;约束「所有数值必须出自事实手册」。
 * ⑥ 数值回查:正则抽取正文数值,与手册比对;未收录 → factRisks(high) 随版本落库。
 * 产物复用 version 表(gen_mode=DEEP 标记在 brief 侧)。
 */
@Slf4j
@Service
public class DeepWriterService {

    private final AiClient aiClient;
    private final ObjectMapper json;
    private final ArticleBriefMapper briefMapper;
    private final ArticleVersionMapper versionMapper;
    /** 项目 mapper(09-10-versions-page-fix:title 回退 project.topic 需取项目) */
    private final ArticleProjectMapper projectMapper;
    /** 系统检索设置(09-09-brief-gen-redesign R3):知识库停用时 rag_status=DISABLED */
    private final com.sparkora.service.SettingService settingService;

    public DeepWriterService(AiClient aiClient, ObjectMapper json,
                             ArticleBriefMapper briefMapper, ArticleVersionMapper versionMapper,
                             ArticleProjectMapper projectMapper,
                             com.sparkora.service.SettingService settingService) {
        this.aiClient = aiClient;
        this.json = json;
        this.briefMapper = briefMapper;
        this.versionMapper = versionMapper;
        this.projectMapper = projectMapper;
        this.settingService = settingService;
    }

    /** 版本标签序列(与 VersionService.LABELS 同口径:A/B/C…按项目内已有版本数续编) */
    private static final String LABELS = "ABCDEFGHIJ";

    /**
     * ⑤ 深度写作并落版本(⑥ 回查结果进 factRisks)。
     * @param briefId  含 fact_sheet 的 brief
     * @param styleId  风格 id(风格画像由调用方注入或此处简化为主题直写)
     * @param styleName 风格名(落版本 style_tag;空回退「深度」;09-10-versions-page-fix 新增)
     * @return 落库的版本 id
     */
    public Long write(Long projectId, Long briefId, String stylePrompt, String styleName) throws Exception {
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null) throw new IllegalArgumentException("brief 不存在");
        JsonNode sheet = json.readTree(b.getFactSheet() == null ? "{}" : b.getFactSheet());
        StringBuilder factCtx = new StringBuilder();
        for (JsonNode e : sheet.path("entries")) {
            factCtx.append("- ").append(e.path("key").asText());
            String v = e.path("value").asText("");
            if (!v.isBlank()) factCtx.append(" = ").append(v);
            double c = e.path("confidence").asDouble(0);
            factCtx.append("(置信 ").append(String.format("%.2f", c)).append(")\n");
        }
        String system = """
                你是资深汽车内容作者。基于【事实手册】与用户锁定需求撰写文章正文。
                铁律:
                1. 正文中出现的所有具体数值(价格/尺寸/续航/百分比等)必须逐字出自下方事实手册,禁止改写/换算/推算。
                2. 手册未覆盖的参数,用定性表述,不得给出具体数值。
                3. 结构清晰,用 Markdown;长度按用户需求。
                """;
        StringBuilder user = new StringBuilder("事实手册(数值唯一来源):\n").append(factCtx).append('\n');
        if (b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank()) {
            user.append("用户锁定需求:\n").append(b.getClarifyAnswers()).append('\n');
        }
        if (stylePrompt != null && !stylePrompt.isBlank()) {
            user.append("风格要求:\n").append(stylePrompt).append('\n');
        }
        user.append("主题与大纲参考 brief(标题候选/核心观点/大纲),直接写正文 Markdown。");
        AiClient.ChatResult cr = aiClient.chat(system, user.toString(), 4096);
        String content = cr.content();

        // ⑥ 数值回查
        List<String> unknown = verifyNumbers(content, b.getFactSheet());
        String factRisks;
        if (unknown.isEmpty()) {
            factRisks = "[]";
        } else {
            List<Map<String, Object>> risks = new ArrayList<>();
            for (String u : unknown) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("claim", "正文数值「" + u + "」未收录于事实手册");
                r.put("riskLevel", "high");
                r.put("suggestion", "该数值无事实手册出处,发布前必须人工核实或删除");
                risks.add(r);
            }
            factRisks = json.writeValueAsString(risks);
            log.warn("数值回查发现未收录数值 briefId={} unknown={}", briefId, unknown);
        }

        var v = new com.sparkora.domain.entity.ArticleVersionEntity();
        v.setProjectId(projectId);
        v.setBriefId(briefId);
        // 检索状态(09-09-brief-gen-redesign R3):知识库停用(全局设置)时标 DISABLED,不与 NO_KNOWLEDGE 混淆;
        // 启用时深度写作阶段的本地检索语义维持 OK(fact_sheet 即引用来源)
        v.setRagStatus(settingService.isKbEnabled() ? "OK" : "DISABLED");
        v.setFactRisks(factRisks);
        v.setAiModel(cr.model());
        v.setTokenUsage(cr.totalTokens());
        v.setContentMd(content);
        // 09-10-versions-page-fix:深度链路此前漏填 title/version_label/style_tag/word_count,
        // 与多版本链路(VersionService.generateOne)对齐补齐,消除版本页 undefined/null 与字数统计为空
        v.setTitle(extractH1(projectId, content));
        v.setVersionLabel(nextLabel(projectId));
        v.setStyleTag(styleName == null || styleName.isBlank() ? "深度" : styleName);
        v.setWordCount(content.length());
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        return v.getId();
    }

    /**
     * 09-10-versions-page-fix:抽取 AI 正文首个 Markdown H1 作为版本 title。
     * 正则多行首匹配「# 标题」;缺失/空白回退 project.topic(project 查询判空防御)。
     */
    private String extractH1(Long projectId, String contentMd) {
        String topic = null;
        try {
            ArticleProjectEntity p = projectMapper.selectById(projectId);
            topic = p != null ? p.getTopic() : null;
        } catch (Exception e) {
            log.warn("取项目 title 回退源失败 projectId={}: {}", projectId, e.getMessage());
        }
        if (contentMd != null) {
            var m = Pattern.compile("(?m)^#\\s+(.+)$").matcher(contentMd);
            if (m.find()) {
                String h1 = m.group(1).trim();
                if (!h1.isBlank()) {
                    // title 列 VARCHAR(200),防御性截断(仿写链路 title 同列)
                    return h1.length() > 200 ? h1.substring(0, 200) : h1;
                }
            }
        }
        return topic;
    }

    /**
     * 09-10-versions-page-fix:按项目内已有版本数取下一版本标签(A/B/C…,同 VersionService 编号口径);
     * 超出 LABELS 长度回退 'A'。project 查询异常时不阻断生成,回退 'A'。
     */
    private String nextLabel(Long projectId) {
        int idx;
        try {
            idx = Math.toIntExact(versionMapper.selectCount(new QueryWrapper<ArticleVersionEntity>()
                    .eq("project_id", projectId)));
        } catch (Exception e) {
            log.warn("统计项目版本数失败 projectId={}: {}", projectId, e.getMessage());
            idx = 0;
        }
        return idx < LABELS.length() ? String.valueOf(LABELS.charAt(idx)) : "A";
    }

    /** 抽取正文数值并比对手册(收录=出现在手册文本任一处:值/claim/sources 串)。 */
    List<String> verifyNumbers(String content, String factSheetJson) throws Exception {
        JsonNode sheet = json.readTree(factSheetJson == null ? "{}" : factSheetJson);
        String haystack = sheet.toString();
        List<String> unknown = new ArrayList<>();
        // 数值形态:纯数字/千分位/小数/「N万」(中文数字万前缀),排除年份与孤立 0-9 单字符
        var m = java.util.regex.Pattern.compile("\\d[\\d,\\.]*\\s*万|\\d{4,7}(?:,\\d{3})*(?:\\.\\d+)?|\\d+\\.(?:\\d+)?%?|\\d+(?:\\.\\d+)?\\s*(?:km|kWh|kW|mm|L/100km|s)").matcher(content);
        while (m.find()) {
            String num = m.group().replaceAll("[ ,万]", "");
            if (num.length() < 2 || "0".equals(num)) continue;
            // 去掉千分位后比对;手册 haystack 含原始值即可通过
            String raw = m.group().trim();
            if (!haystack.contains(raw) && !haystack.contains(num)) {
                if (!unknown.contains(m.group().trim())) unknown.add(m.group().trim());
            }
        }
        return unknown;
    }
}