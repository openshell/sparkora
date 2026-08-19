package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文章多版本生成服务。基于 brief 循环生成 N 版正文（默认 2 版），每版用不同风格 system prompt，
 * 单版失败不影响其他版。
 *
 * 状态机：READY(brief就绪) → GENERATING_VERSIONS → VERSIONS_READY
 *  失败回 READY 并写 lastVersionError（任一版都没生成成功才算整体失败）。
 *
 * 事务边界同 BriefService：置状态短事务先提交，AI 调用无事务，最后写版本+置状态再提交。
 */
@Slf4j
@Service
public class VersionService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleBriefMapper briefMapper;
    private final ArticleVersionMapper versionMapper;
    private final AiClient aiClient;
    private final ObjectMapper json;

    /** 预设风格方案：label + styleTag + systemPrompt 片段。默认 2 版。 */
    private static final List<Map<String, String>> STYLE_PRESETS = List.of(
            Map.of("label", "A", "styleTag", "正式严谨",
                    "sys", "你是资深公众号主笔。用正式、严谨、信息密度高的语气撰写，段落清晰，论据充分，少口语化表达。"),
            Map.of("label", "B", "styleTag", "活泼易读",
                    "sys", "你是擅长抓注意力的公众号作者。用活泼、贴近读者的语气，善用短句与提问，适当口语化但保持专业。"),
            Map.of("label", "C", "styleTag", "干货清单",
                    "sys", "你是干货型公众号作者。结构化输出，多用分点与清单，每段给出可直接照做的建议。")
    );

    public VersionService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                          ArticleVersionMapper versionMapper, AiClient aiClient, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.versionMapper = versionMapper;
        this.aiClient = aiClient;
        this.json = json;
    }

    /**
     * 生成 N 版正文（默认 2）。
     * @return 生成的版本列表（可能少于 N，若某版失败则跳过）
     */
    public List<ArticleVersionEntity> generate(Long projectId, int count) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (p.getCurrentBriefId() == null) throw new IllegalStateException("尚未生成 brief，无法生成版本");
        ArticleBriefEntity brief = briefMapper.selectById(p.getCurrentBriefId());
        if (brief == null) throw new IllegalStateException("brief 不存在");

        int n = Math.max(1, Math.min(count, STYLE_PRESETS.size()));

        // 1) 置进行中
        p.setStatus("GENERATING_VERSIONS");
        p.setLastVersionError(null);
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);

        List<ArticleVersionEntity> created = new ArrayList<>();
        List<String> perVersionErrors = new ArrayList<>();
        try {
            // 2) 循环单版生成
            for (int i = 0; i < n; i++) {
                Map<String, String> preset = STYLE_PRESETS.get(i);
                try {
                    ArticleVersionEntity v = generateOne(p, brief, preset);
                    versionMapper.insert(v);
                    created.add(v);
                } catch (Exception e) {
                    log.warn("版本 {} 生成失败 project={}: {}", preset.get("label"), projectId, e.getMessage());
                    perVersionErrors.add("[" + preset.get("label") + "] " + e.getMessage());
                }
            }

            if (created.isEmpty()) {
                throw new AiException("全部版本生成失败: " + String.join("; ", perVersionErrors), null);
            }

            // 3) 默认选第一版为当前
            ArticleVersionEntity first = created.get(0);
            p.setCurrentVersionId(first.getId());
            p.setStatus("VERSIONS_READY");
            p.setLastVersionError(perVersionErrors.isEmpty() ? null : "部分版本失败: " + String.join("; ", perVersionErrors));
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            return created;

        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            // 整体失败回 READY（保留 brief）
            p = projectMapper.selectById(projectId);
            p.setStatus("READY");
            p.setLastVersionError(reason);
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            throw new AiException("版本生成失败: " + reason, e);
        }
    }

    private ArticleVersionEntity generateOne(ArticleProjectEntity p, ArticleBriefEntity brief, Map<String, String> preset) throws Exception {
        AiClient.ChatResult cr = aiClient.chatJson(
                preset.get("sys") + "\n\n只输出 JSON 对象：{\"title\":\"本版标题\",\"contentMd\":\"完整 Markdown 正文\"}。contentMd 内直接写 Markdown，不要包代码块围栏，不要额外说明。所有内容中文。",
                buildUserPrompt(p, brief),
                4096);
        var node = json.readTree(cr.content());
        String title = node.path("title").asText("");
        String contentMd = node.path("contentMd").asText("");
        if (contentMd.isBlank()) throw new AiException("contentMd 为空（可能 max_tokens 不足被截断）", null);

        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setProjectId(p.getId());
        v.setBriefId(brief.getId());
        v.setTitle(title.isBlank() ? p.getTopic() : title);
        v.setContentMd(contentMd);
        v.setVersionLabel(preset.get("label"));
        v.setStyleTag(preset.get("styleTag"));
        v.setAiModel(cr.model());
        v.setTokenUsage(cr.totalTokens());
        v.setWordCount(contentMd.length());
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    private String buildUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        return """
                主题：%s
                关键词：%s
                目标读者：%s
                目标字数：%s

                创作简报（基于此展开，标题可从中候选调整）：
                - 标题候选：%s
                - 核心观点：%s
                - 大纲：%s
                - 事实风险点（写作时注意表述，按建议弱化或标注）：%s

                请按大纲完整展开成公众号文章正文（Markdown）。
                """.formatted(
                nv(p.getTopic()), nv(p.getKeywords()), nv(p.getAudience()),
                p.getWordCountTarget() == null ? "1500" : p.getWordCountTarget(),
                nv(b.getTitleCandidates()), nv(b.getCoreViewpoints()),
                nv(b.getOutline()), nv(b.getFactRisks()));
    }

    /** 列出项目全部版本（按创建序）。 */
    public List<ArticleVersionEntity> list(Long projectId) {
        return versionMapper.selectList(new QueryWrapper<ArticleVersionEntity>()
                .eq("project_id", projectId).orderByAsc("id"));
    }

    /** 设定当前版本。 */
    public void setCurrent(Long projectId, Long versionId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        p.setCurrentVersionId(versionId);
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "未指定" : s; }
}
