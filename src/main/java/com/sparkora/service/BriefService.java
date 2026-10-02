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
 * 状态推进/回退/错误列写入已收敛到 ProjectStatusService（09-27-state-machine-service），本服务纯委托。
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
    /** 项目状态机唯一写权持有者(抢占/推进/回退/错误列)。 */
    private final ProjectStatusService statusService;

    public BriefService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                        AiClient aiClient, ObjectMapper json, ProjectStatusService statusService) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.aiClient = aiClient;
        this.json = json;
        this.statusService = statusService;
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
        if (statusService.stuckGenerating(p)) {
            throw new IllegalStateException("该项目正在生成中，请稍候（刷新页面可查看进度）");
        }
        // 原子抢占置 GENERATING_BRIEF(仅 DRAFT/READY 或陈旧生成中),claimed==0 抛 409 守卫提示
        statusService.claimBriefGenerating(projectId, p, "生成简报");
        p.setStatus("GENERATING_BRIEF");

        try {
            // AI 调用（无事务）：以事实手册为主要事实来源 + 用户锁定需求产出结构化简报。
            // C2：schema 由 BriefDto 类型单一派生（structured 内 validateSchema 自纠错）；截断/空内容/类型
            // 转换失败仍由外层「提额一倍重试一次」兜底（与 schema 自纠错解耦）。
            AiClient.ChatResult cr;
            BriefDto dto;
            try {
                // R6(09-26):R5 放大事实手册后,brief 66 实测 17 条/10294 字,2048 额度系统性不足,
                // 首次 8192;失败(截断 finish_reason=length / 空内容 / 反序列化失败)翻倍提额 16384 重试一次。
                AiClient.TypedResult<BriefDto> tr =
                        aiClient.structured(buildDeepBriefSystemPrompt(), buildDeepBriefUserPrompt(p, b), 8192, BriefDto.class);
                dto = tr.entity();
                cr = tr.chat();
            } catch (Exception first) {
                // 重试独立实现(不抽公共 helper、不与已删除的 FAST 路径共用):附纠错说明提示模型只输出完整合法 JSON。
                log.warn("深度简报首次生成失败,提额重试(16384) project={} brief={}: {}", projectId, briefId, first.getMessage());
                AiClient.TypedResult<BriefDto> tr = aiClient.structured(buildDeepBriefSystemPrompt(),
                        buildDeepBriefUserPrompt(p, b)
                                + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象,确保字段齐全。",
                        16384, BriefDto.class);
                dto = tr.entity();
                cr = tr.chat();
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
            statusService.advanceReady(projectId, b.getId(), java.util.Map.of());
            return b;
        } catch (Exception e) {
            // 失败回 DRAFT 并记录原因；brief 行不动（无简报字段，前端保留深度面板可重试）。
            // 回退限定生成中状态:并发已推进(VERSIONS_READY 及之后)时不覆盖,保守安全。
            log.warn("深度简报生成失败 project={} brief={}: {}", projectId, briefId, e.getMessage(), e);
            statusService.failBriefToDraft(projectId, e.getMessage());
            throw new AiException("深度简报生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 深度简报 system prompt（C1 外置模板 {@code prompts/brief/deep-brief-system.st}）：
     * 以事实手册为唯一事实来源，数值/参数必须逐字出自手册。
     */
    private String buildDeepBriefSystemPrompt() {
        // C2:{{schema}} 由 BriefDto 类型派生(单一来源),prompt 不再内联 JSON schema 字面量
        return com.sparkora.ai.PromptTemplateLoader.render("brief/deep-brief-system.st",
                java.util.Map.of("schema", AiClient.jsonSchema(BriefDto.class)));
    }

    /** 深度简报 user prompt：主题 + 内容描述 + 目标读者 + 目标字数 + 锁定需求 + 研究假设 + 事实手册（含来源与置信度）。 */
    private String buildDeepBriefUserPrompt(ArticleProjectEntity p, ArticleBriefEntity b) {
        StringBuilder user = new StringBuilder("主题:").append(p.getTopic()).append('\n');
        // 10-02 R4:创建输入注入简报 prompt(非空才加;目标字数缺省口径对齐其它链路 1500)
        if (p.getContentDescription() != null && !p.getContentDescription().isBlank()) {
            user.append("内容描述:").append(p.getContentDescription()).append('\n');
        }
        if (p.getAudience() != null && !p.getAudience().isBlank()) {
            user.append("目标读者:").append(p.getAudience()).append('\n');
        }
        user.append("目标字数:").append(p.getWordCountTarget() == null ? 1500 : p.getWordCountTarget()).append('\n');
        if (b.getClarifyAnswers() != null && !b.getClarifyAnswers().isBlank()) {
            user.append("用户锁定需求:").append(b.getClarifyAnswers()).append('\n');
        }
        // R6(09-27-tavily-extract-kind-hypotheses):注入 research_plan.hypotheses,使 coreViewpoints
        // 显式回应「假设被证实/推翻」。research_plan 缺失/无 hypotheses/畸形 → 跳过(兼容退化,不报错)。
        String hypotheses = hypothesesBlock(b.getResearchPlan());
        if (hypotheses != null) user.append(hypotheses);
        user.append("事实手册(唯一事实来源,数值逐字引用):").append(b.getFactSheet());
        return user.toString();
    }

    /**
     * R6:从 research_plan JSON 提取 hypotheses 并格式化为 prompt 块;缺失/无/非数组 → null(跳过)。
     * 解析全程容错,绝不因研究计划异常阻断简报生成。
     */
    private String hypothesesBlock(String researchPlan) {
        if (researchPlan == null || researchPlan.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode plan = json.readTree(researchPlan);
            com.fasterxml.jackson.databind.JsonNode hs = plan.path("hypotheses");
            if (!hs.isArray() || hs.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("研究假设(研究前所立,请在核心观点中回应其是否被手册证实或推翻):\n");
            boolean any = false;
            for (com.fasterxml.jackson.databind.JsonNode h : hs) {
                String text = h.isTextual() ? h.asText() : h.toString();
                if (text == null || text.isBlank()) continue;
                sb.append("- ").append(text).append('\n');
                any = true;
            }
            return any ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("研究假设注入简报 prompt 失败,跳过: {}", e.getMessage());
            return null;
        }
    }
}
