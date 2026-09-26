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
 *   DRAFT ──generateFromFactSheet──▶ GENERATING_BRIEF ──成功──▶ READY
 *                                              └─失败──▶ DRAFT（写 lastBriefError）
 *
 * 事务边界刻意分阶段、各自短事务：AI 调用耗时可达数秒~数十秒，不能包在一个 DB 事务里阻塞连接。
 *  故「置 GENERATING_BRIEF」先提交，让前端能看到进行中；AI 调用无事务；最后写 brief + 置 READY 再提交。
 *
 * 注：快速模式（FAST）简报生成已随模式收敛删除，唯一简报链路为深度事实手册链路 generateFromFactSheet。
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

    /** 取项目当前 brief（无则 null）。 */
    public ArticleBriefEntity currentBrief(Long projectId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null || p.getCurrentBriefId() == null) return null;
        return briefMapper.selectById(p.getCurrentBriefId());
    }

    /**
     * R3:知识引用明细序列化(rag_citations 列)。OK 且有命中才落;异常不阻断生成(落 null)。
     * 截断防御:超 8000 字符整体置 null(引用明细是辅助信息,不能因超列毁掉本次生成)。
     */
    static String citationsJson(CarRagService.RagResult rag) {
        try {
            if (rag == null || rag.citations() == null || rag.citations().isEmpty()) return null;
            String s = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rag.citations());
            return s.length() > 8000 ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 基于深度研究事实手册生成简报（S9 增补）：研究完成后自动调用，也可 POST /deep/brief 手动重试。
     * 落简报字段到同一条 DEEP brief 行并把项目状态机推到 READY（currentBriefId 指向该行），
     * 前端简报页据此正常展示——修复「确定研究计划后没有简报页面」的结构性缺陷。
     * 状态守护：DRAFT/READY 可触发，生成中未过期拒绝，VERSIONS_READY 及之后拒绝。
     */
    public ArticleBriefEntity generateFromFactSheet(Long projectId, Long briefId) {
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        ArticleBriefEntity b = briefMapper.selectById(briefId);
        if (b == null || !projectId.equals(b.getProjectId()) || !"DEEP".equals(b.getGenMode()))
            throw new IllegalArgumentException("深度 brief 不存在");
        if (b.getFactSheet() == null || b.getFactSheet().isBlank())
            throw new IllegalStateException("事实手册尚未生成，请先完成研究");
        if (stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }
        int claimed = claimGenerating(projectId);
        if (claimed == 0) throw new IllegalStateException(projectStatusGuardMsg(p, "生成简报"));
        p.setStatus("GENERATING_BRIEF");

        try {
            // AI 调用（无事务）：以事实手册为主要事实来源 + 用户锁定需求产出结构化简报
            AiClient.ChatResult cr;
            BriefDto dto;
            try {
                // R6(09-26):R5 放大事实手册后,brief 66 实测 17 条/10294 字,2048 额度系统性不足,
                // 首次 8192;失败(截断 finish_reason=length / 空内容 / 非法 JSON)翻倍提额 16384 重试一次。
                cr = aiClient.chatJson(buildDeepBriefSystemPrompt(), buildDeepBriefUserPrompt(p, b), 8192);
                dto = json.readValue(AiClient.sanitizeAiJson(cr.content()), BriefDto.class);
            } catch (Exception first) {
                // 重试独立实现(不抽公共 helper、不与已删除的 FAST 路径共用):附纠错说明提示模型只输出完整合法 JSON。
                log.warn("深度简报首次生成失败,提额重试(16384) project={} brief={}: {}", projectId, briefId, first.getMessage());
                cr = aiClient.chatJson(buildDeepBriefSystemPrompt(),
                        buildDeepBriefUserPrompt(p, b)
                                + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象,确保字段齐全。",
                        16384);
                dto = json.readValue(AiClient.sanitizeAiJson(cr.content()), BriefDto.class);
            }

            // 落同一条 DEEP brief 行（gen_mode 保持 DEEP，研究产物不覆盖）
            b.setTitleCandidates(json.writeValueAsString(dto.getTitleCandidates()));
            b.setAudienceRefine(dto.getAudienceRefine());
            b.setCoreViewpoints(json.writeValueAsString(dto.getCoreViewpoints()));
            b.setOutline(json.writeValueAsString(dto.getOutline()));
            b.setFactRisks(json.writeValueAsString(dto.getFactRisks()));
            b.setAiModel(cr.model());
            b.setTokenUsage(cr.totalTokens());
            briefMapper.updateById(b);

            // 条件更新:仅当仍处于本次抢占置的 GENERATING_BRIEF 才推进(并发已推进下游状态时不回退)
            projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .eq("status", "GENERATING_BRIEF")
                    .set("current_brief_id", b.getId())
                    .set("status", "READY")
                    .set("last_brief_error", null)
                    .set("updated_at", LocalDateTime.now()));
            return b;
        } catch (Exception e) {
            // 失败回 DRAFT 并记录原因；brief 行不动（无简报字段，前端保留深度面板可重试）。
            // 条件更新限定生成中状态:并发已推进(VERSIONS_READY 及之后)时不覆盖,保守安全。
            String reason = e.getMessage();
            if (reason != null && reason.length() > 1000) reason = reason.substring(0, 1000);
            log.warn("深度简报生成失败 project={} brief={}: {}", projectId, briefId, reason, e);
            projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                    .set("status", "DRAFT")
                    .set("last_brief_error", reason)
                    .set("updated_at", LocalDateTime.now()));
            throw new AiException("深度简报生成失败: " + reason, e);
        }
    }

    /** 原子抢占置 GENERATING_BRIEF（深度链路）：仅 DRAFT/READY 或陈旧生成中可成功。 */
    private int claimGenerating(Long projectId) {
        java.time.LocalDateTime staleCutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(STALE_GENERATING_MS));
        return projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "DRAFT", "READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_BRIEF")
                .set("last_brief_error", null)
                .set("updated_at", LocalDateTime.now()));
    }

    /** 深度简报 system prompt：以事实手册为唯一事实来源，数值/参数必须逐字出自手册。 */
    private String buildDeepBriefSystemPrompt() {
        return """
                你是新媒体内容策划专家。基于「事实手册」和用户已锁定的需求,输出一份结构化创作 Brief。
                铁律:手册中出现的数值/参数/价格必须逐字引用,不得改写或补充手册外数字;手册未覆盖的表述放入 factRisks。
                手册条目可能带「snippet」原始证据(检索命中正文),背景/来龙去脉类素材(行业背景、企业战略、长期目标等)应优先从 snippet 证据中提取,再纳入观点与大纲。
                只输出 JSON 对象，字段如下，不要任何额外文字：
                {
                  "titleCandidates": ["3个标题候选"],
                  "audienceRefine": "细化后的目标读者一句话描述",
                  "coreViewpoints": ["2-4条核心观点"],
                  "outline": [{"heading":"章节标题","subPoints":["2-4个要点"]}],
                  "factRisks": [{"claim":"文中可能提到的事实性表述","riskLevel":"low|medium|high","suggestion":"核实/表述建议"}]
                }
                factRisks:从手册的 warnings 与低置信条目派生,至少 1 条。所有内容用中文。
                """;
    }

    /** 深度简报 user prompt：主题 + 锁定需求 + 事实手册（含来源与置信度）。 */
    private String buildDeepBriefUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        StringBuilder user = new StringBuilder("主题:").append(p.getTopic()).append('\n');
        if (b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank()) {
            user.append("用户锁定需求:").append(b.getClarifyAnswers()).append('\n');
        }
        user.append("事实手册(唯一事实来源,数值逐字引用):").append(b.getFactSheet());
        return user.toString();
    }

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
