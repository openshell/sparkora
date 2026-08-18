package com.sparkora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.BriefDto;
import com.sparkora.domain.entity.ArticleBriefEntity;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleBriefMapper;
import com.sparkora.mapper.ArticleProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 创作 Brief 生成服务。
 *
 * 状态机（调用者视角）：
 *   DRAFT ──generate──▶ GENERATING_BRIEF ──成功──▶ READY
 *                                  └─失败──▶ DRAFT（写 lastBriefError）
 *
 * 事务边界刻意分阶段、各自短事务：AI 调用耗时可达数秒~数十秒，不能包在一个 DB 事务里阻塞连接。
 *  故「置 GENERATING_BRIEF」先提交，让前端能看到进行中；AI 调用无事务；最后写 brief + 置 READY 再提交。
 */
@Slf4j
@Service
public class BriefService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleBriefMapper briefMapper;
    private final AiClient aiClient;
    private final ObjectMapper json;

    public BriefService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                        AiClient aiClient, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.aiClient = aiClient;
        this.json = json;
    }

    /**
     * 同步生成 brief（S1 阶段同步即可；前端 loading 等待。后续 S2 若要可改异步+轮询）。
     * @return 新生成的 brief 实体（已含 id）
     */
    public ArticleBriefEntity generate(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");

        // 1) 置进行中（短事务，立即持久化，便于前端观察）
        p.setStatus("GENERATING_BRIEF");
        p.setLastBriefError(null);
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);

        try {
            // 2) 调 AI（无事务，慢操作）
            AiClient.ChatResult cr = aiClient.chatJson(
                    buildSystemPrompt(),
                    buildUserPrompt(p),
                    2048);
            BriefDto dto = json.readValue(cr.content(), BriefDto.class);

            // 3) 写 brief 行 + 置 READY（短事务）
            ArticleBriefEntity b = new ArticleBriefEntity();
            b.setProjectId(projectId);
            b.setTitleCandidates(json.writeValueAsString(dto.getTitleCandidates()));
            b.setAudienceRefine(dto.getAudienceRefine());
            b.setCoreViewpoints(json.writeValueAsString(dto.getCoreViewpoints()));
            b.setOutline(json.writeValueAsString(dto.getOutline()));
            b.setFactRisks(json.writeValueAsString(dto.getFactRisks()));
            b.setAiModel(cr.model());
            b.setTokenUsage(cr.totalTokens());
            b.setCreatedAt(LocalDateTime.now());
            briefMapper.insert(b);

            p.setCurrentBriefId(b.getId());
            p.setStatus("READY");
            p.setLastBriefError(null);
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            return b;

        } catch (Exception e) {
            // 失败回 DRAFT 并记录原因（截断防超列）
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            log.warn("brief 生成失败 project={}: {}", projectId, reason, e);
            p.setStatus("DRAFT");
            p.setLastBriefError(reason);
            p.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(p);
            throw new AiException("brief 生成失败: " + reason, e);
        }
    }

    /** 取项目当前 brief（无则 null）。 */
    public ArticleBriefEntity currentBrief(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null || p.getCurrentBriefId() == null) return null;
        return briefMapper.selectById(p.getCurrentBriefId());
    }

    private String buildSystemPrompt() {
        return """
                你是新媒体内容策划专家。根据用户的主题/关键词/受众，输出一份结构化创作 Brief，要求事实严谨、对不确定的数据主动标记风险。
                只输出 JSON 对象，字段如下，不要任何额外文字：
                {
                  "titleCandidates": ["3个标题候选"],
                  "audienceRefine": "细化后的目标读者一句话描述",
                  "coreViewpoints": ["2-4条核心观点"],
                  "outline": [{"heading":"章节标题","subPoints":["2-4个要点"]}],
                  "factRisks": [{"claim":"文中可能提到的事实性表述","riskLevel":"low|medium|high","suggestion":"核实/表述建议"}]
                }
                factRisks 至少给1条（哪怕 riskLevel=low）。所有内容用中文。
                """;
    }

    private String buildUserPrompt(ArticleProjectEntity p) {
        return """
                主题：%s
                关键词：%s
                目标读者：%s
                目标字数：%s
                备注：%s
                """.formatted(
                nv(p.getTopic()),
                nv(p.getKeywords()),
                nv(p.getAudience()),
                p.getWordCountTarget() == null ? "未指定" : p.getWordCountTarget(),
                nv(p.getRemark()));
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "未指定" : s; }
}
