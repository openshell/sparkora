package com.sparkora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.BriefDto;
import com.sparkora.car.service.CarRagService;
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
    private final CarRagService ragService;
    private final ObjectMapper json;

    public BriefService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                        AiClient aiClient, CarRagService ragService, ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.aiClient = aiClient;
        this.ragService = ragService;
        this.json = json;
    }

    /** 生成中状态超过该时长视为陈旧（JVM 中途死亡/重启残留），允许重新触发以自愈。 */
    private static final long STALE_GENERATING_MS = 10 * 60 * 1000L;

    /** 项目是否卡在生成中状态（未过期）。 */
    private boolean stuckGenerating(ArticleProjectEntity p) {
        String s = p.getStatus();
        boolean generating = "GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s);
        if (!generating) return false;
        // updated_at 超过阈值 = 生成进程已不存在（正常生成最长 AI_TIMEOUT_MS 级别，10 分钟足够宽裕）
        return p.getUpdatedAt() != null
                && p.getUpdatedAt().isAfter(LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS)));
    }

    /**
     * 同步生成 brief（S1 阶段同步即可；前端 loading 等待。后续 S2 若要可改异步+轮询）。
     * @return 新生成的 brief 实体（已含 id）
     */
    public ArticleBriefEntity generate(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        // 并发防护:正在生成中（未过期）时拒绝重复触发(双开页面/前端状态恢复失效场景的接口层兜底);
        // JVM 中途死亡遗留的 GENERATING_* 状态(超过 10 分钟)视为陈旧,放行重新生成以自愈
        if (stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }

        // 1) 条件更新置进行中（原子抢占,消除 check-then-set 竞态）:
        //    仅当「DRAFT/READY(正常生成/重生成)」或「生成中且已陈旧(超阈值,进程已死,自愈)」才生效;
        //    陈旧分支必须限定生成中状态,否则任何 updated_at 较旧的下游状态都会被误放行、状态机回退。
        //    状态守护:VERSIONS_READY 及之后已触发下一步,再生成简报会把状态机拉回 READY,拒绝。
        java.time.LocalDateTime staleCutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "DRAFT", "READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_BRIEF")
                .set("last_brief_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(projectStatusGuardMsg(p, "重新生成简报"));
        p.setStatus("GENERATING_BRIEF");

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
        String base = """
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
        // S6:补充信息(用户个人见解/独家资讯等)作为创作素材注入,要求融入 brief 观点/大纲
        if (p.getExtraInfo() != null && !p.getExtraInfo().isBlank()) {
            base += "\n\n【用户补充信息(个人见解/独家资讯等),请作为创作素材融入核心观点与大纲,不得遗漏关键信息】\n" + p.getExtraInfo();
        }
        // S6 RAG:项目关联车型时,检索车型知识库注入权威参数作为事实约束
        if (p.getCarModelId() != null) {
            try {
                String ctx = ragService.buildContext(p.getCarModelId(), p.getTopic(), 8, 0.3);
                if (!ctx.isBlank()) {
                    base += "\n\n【车型知识库权威数据,请严格依据这些数据撰写,不得编造;数据缺失时在 factRisks 标注】\n" + ctx;
                }
            } catch (Exception e) {
                log.warn("brief RAG 检索失败 project={}: {}", p.getId(), e.getMessage());
            }
        }
        return base;
    }

    private static String nv(String s) { return s == null || s.isBlank() ? "未指定" : s; }

    /**
     * 状态守护拒绝文案:生成中提示稍候;已推进到下游状态(VERSIONS_READY 及之后)说明该步已完成,
     * 重触发会把状态机拉回本步,明确告知不支持(需要重来请新建项目或回退到对应状态再操作)。
     */
    static String projectStatusGuardMsg(ArticleProjectEntity p, String action) {
        String s = p.getStatus();
        if ("GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s))
            return "该项目正在生成中，请稍候（刷新页面可查看进度）";
        return "项目状态为「" + s + "」，" + action + "仅在对应前置状态可用；下游步骤已触发，不支持回退重做";
    }
}
