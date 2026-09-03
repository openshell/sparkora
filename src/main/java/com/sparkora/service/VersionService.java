package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.car.service.CarRagService;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.StyleProfileEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.StyleProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文章多版本生成服务。基于 brief + 用户从风格库选择的若干风格，每选一个风格生成一版正文。
 *
 * 风格来源：sparkora_style_profile.tone_guidance（由用户样文提炼入库），供用户选择，
 *  不再硬编码预设风格。
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
    private final StyleProfileMapper styleMapper;
    private final AiClient aiClient;
    private final CarRagService ragService;
    private final ArticleProjectCarService carService;
    private final ObjectMapper json;

    private static final String LABELS = "ABCDEFGHIJ";

    public VersionService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                          ArticleVersionMapper versionMapper, StyleProfileMapper styleMapper,
                          AiClient aiClient, CarRagService ragService,
                          ArticleProjectCarService carService, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.versionMapper = versionMapper;
        this.styleMapper = styleMapper;
        this.aiClient = aiClient;
        this.ragService = ragService;
        this.carService = carService;
        this.json = json;
    }

    /**
     * 按用户选中的风格生成多版正文。每选一个风格生成一版（风格 toneGuidance 作为 system prompt 片段）。
     * @param styleIds 用户从风格库选中的风格 id 列表（至少 1 个，最多 10 个）
     * @return 生成的版本列表（可能少于 styleIds 数，若某版失败则跳过）
     */
    /** 生成中状态超过该时长视为陈旧（JVM 中途死亡/重启残留），允许重新触发以自愈。 */
    private static final long STALE_GENERATING_MS = 10 * 60 * 1000L;

    /** 项目是否卡在生成中状态（未过期）。 */
    private boolean stuckGenerating(ArticleProjectEntity p) {
        String s = p.getStatus();
        boolean generating = "GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s);
        if (!generating) return false;
        return p.getUpdatedAt() != null
                && p.getUpdatedAt().isAfter(LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS)));
    }

    public List<ArticleVersionEntity> generate(Long projectId, List<Long> styleIds) {
        if (styleIds == null || styleIds.isEmpty())
            throw new IllegalArgumentException("至少选择一个风格");
        if (styleIds.size() > LABELS.length())
            throw new IllegalArgumentException("一次最多生成 " + LABELS.length() + " 版");

        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        // 并发防护:正在生成中（未过期）时拒绝重复触发;陈旧状态(超 10 分钟,进程已死)放行自愈
        if (stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }
        if (p.getCurrentBriefId() == null) throw new NotReadyException("尚未生成 brief，无法生成版本");
        ArticleBriefEntity brief = briefMapper.selectById(p.getCurrentBriefId());
        if (brief == null) throw new NotReadyException("brief 不存在");

        List<StyleProfileEntity> styles = styleMapper.selectBatchIds(styleIds);
        if (styles.isEmpty()) throw new IllegalArgumentException("所选风格不存在");

        // 1) 条件更新置进行中（原子抢占,消除 check-then-set 竞态）:
        //    仅当「READY/VERSIONS_READY(首生成或追加)」或「生成中且已陈旧(超阈值,进程已死,自愈)」才生效;
        //    陈旧分支必须限定生成中状态,否则任何 updated_at 较旧的下游状态都会被误放行、状态机回退。
        //    状态守护:VERSIONS_READY 之后(PUBLISHED_DRAFT)已触发下一步,再生成版本会把状态机拉回 VERSIONS_READY,拒绝。
        java.time.LocalDateTime staleCutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "READY", "VERSIONS_READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_VERSIONS")
                .set("last_version_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(BriefService.projectStatusGuardMsg(p, "生成版本"));
        p.setStatus("GENERATING_VERSIONS");

        List<ArticleVersionEntity> created = new ArrayList<>();
        List<String> perVersionErrors = new ArrayList<>();
        try {
            // 2) 每个选中风格生成一版
            int i = 0;
            for (StyleProfileEntity style : styles) {
                String label = String.valueOf(LABELS.charAt(i++));
                try {
                    ArticleVersionEntity v = generateOne(p, brief, style, label);
                    versionMapper.insert(v);
                    created.add(v);
                } catch (Exception e) {
                    log.warn("版本 {}({}) 生成失败 project={}: {}", label, style.getName(), projectId, e.getMessage());
                    perVersionErrors.add("[" + label + ":" + style.getName() + "] " + e.getMessage());
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
            p = projectMapper.selectById(projectId);
            p.setStatus("READY");
            p.setLastVersionError(reason);
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            throw new AiException("版本生成失败: " + reason, e);
        }
    }

    private ArticleVersionEntity generateOne(ArticleProjectEntity p, ArticleBriefEntity brief,
                                             StyleProfileEntity style, String label) throws Exception {
        String sys = (style.getToneGuidance() == null ? "" : style.getToneGuidance())
                + "\n\n只输出 JSON 对象：{\"title\":\"本版标题\",\"contentMd\":\"完整 Markdown 正文\"}。"
                + "contentMd 内直接写 Markdown，不要包代码块围栏，不要额外说明。所有内容中文。";
        AiClient.ChatResult cr = aiClient.chatJson(sys, buildUserPrompt(p, brief), 4096);
        var node = json.readTree(cr.content());
        String title = node.path("title").asText("");
        String contentMd = node.path("contentMd").asText("");
        if (contentMd.isBlank()) throw new AiException("contentMd 为空（可能 max_tokens 不足被截断）", null);

        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setProjectId(p.getId());
        v.setBriefId(brief.getId());
        v.setTitle(title.isBlank() ? p.getTopic() : title);
        v.setContentMd(contentMd);
        v.setVersionLabel(label);
        v.setStyleTag(style.getName());
        v.setAiModel(cr.model());
        v.setTokenUsage(cr.totalTokens());
        v.setWordCount(contentMd.length());
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    private String buildUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        String base = """
                主题：%s
                关键词：%s
                目标读者：%s
                目标字数：%s

                创作简报（基于此展开，标题可从中候选调整）：
                - 标题候选：%s
                - 核心观点：%s
                - 大纲：%s
                - 事实风险点（写作时注意表述，按建议弱化或标注）：%s

                请按大纲完整展开成公众号文章正文（Markdown），严格遵循指定风格。
                """.formatted(
                nv(p.getTopic()), nv(p.getKeywords()), nv(p.getAudience()),
                p.getWordCountTarget() == null ? "1500" : p.getWordCountTarget(),
                nv(b.getTitleCandidates()), nv(b.getCoreViewpoints()),
                nv(b.getOutline()), nv(b.getFactRisks()));
        // S6:简报阶段用户点选的标题,作为本版标题偏好(优先采用,可微调)
        if (p.getSelectedTitle() != null && !p.getSelectedTitle().isBlank()) {
            base += "\n\n【用户已选定标题,请优先采用该标题作为本版标题(可微调措辞,勿偏离原意)】\n" + p.getSelectedTitle();
        }
        // S6:补充信息(用户个人见解/独家资讯等)作为创作素材注入,要求融入正文
        if (p.getExtraInfo() != null && !p.getExtraInfo().isBlank()) {
            base += "\n\n【用户补充信息(个人见解/独家资讯等),请在正文中自然融入,不得遗漏关键信息】\n" + p.getExtraInfo();
        }
        // S6 RAG:项目关联车型时,跨车型检索知识库注入权威参数作为事实约束
        List<Long> modelIds = carService.listModelIds(p.getId());
        if (!modelIds.isEmpty()) {
            try {
                String ctx = ragService.buildContextForModels(modelIds, p.getTopic(), 8, 0.3);
                if (!ctx.isBlank()) {
                    base += "\n\n【车型知识库权威数据,请严格依据这些数据撰写,不得编造;数据缺失时不要臆造】\n" + ctx;
                }
            } catch (Exception e) {
                log.warn("版本 RAG 检索失败 project={}: {}", p.getId(), e.getMessage());
            }
        }
        return base;
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

    /**
     * 保存版本正文(S4 预览页左栏编辑)。仅更新 contentMd;字数上限随 brief 生成口径,这里只防御超长。
     */
    public void updateContent(Long projectId, Long versionId, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) throw new IllegalArgumentException("正文不能为空");
        if (contentMd.length() > 200_000) throw new IllegalArgumentException("正文过长(上限 20 万字符)");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        v.setContentMd(contentMd);
        versionMapper.updateById(v);
    }

    /**
     * 编辑版本标题(S6)。仅更新 title;空串清除。
     */
    public void updateTitle(Long projectId, Long versionId, String title) {
        if (title != null && title.length() > 200) throw new IllegalArgumentException("标题不能超过 200 字");
        ArticleVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || !v.getProjectId().equals(projectId))
            throw new IllegalArgumentException("版本不存在或不属于该项目");
        v.setTitle(title == null || title.isBlank() ? null : title);
        versionMapper.updateById(v);
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "未指定" : s; }
}
