package com.sparkora.service;

import com.sparkora.ai.AiException;
import com.sparkora.car.service.CarRagService;
import com.sparkora.deep.service.BlueprintService;
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
 *  故「置 GENERATING_BRIEF」先提交，让前端能看到进行中；AI 调用无事务；最后置 READY 再提交。
 *
 * 状态推进/回退/错误列写入已收敛到 ProjectStatusService（09-27-state-machine-service），本服务纯委托。
 *
 * <p>C3（10-03-gen-cognitive-redesign）：认知产物由 {@link BlueprintService} 生成（写作蓝图），
 * 本服务只保留状态机委托与事务边界，{@link #generateFromFactSheet} 校验/抢占/推进后委托蓝图服务；
 * 旧 {@code BriefDto}（titleCandidates/outline/factRisks…）生产链路已退役，不再写入。
 *
 * 注：快速模式（FAST）简报生成已随模式收敛删除，唯一简报链路为深度事实手册链路 generateFromFactSheet。
 */
@Slf4j
@Service
public class BriefService {

    private final ArticleProjectMapper projectMapper;
    private final ArticleBriefMapper briefMapper;
    /** 项目状态机唯一写权持有者(抢占/推进/回退/错误列)。 */
    private final ProjectStatusService statusService;
    /** C3 认知产物生成(写作蓝图)+ 人工评审门。 */
    private final BlueprintService blueprintService;

    public BriefService(ArticleProjectMapper projectMapper, ArticleBriefMapper briefMapper,
                        ProjectStatusService statusService, BlueprintService blueprintService) {
        this.projectMapper = projectMapper;
        this.briefMapper = briefMapper;
        this.statusService = statusService;
        this.blueprintService = blueprintService;
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
     * 基于深度研究事实手册生成写作蓝图（C3 起委托 {@link BlueprintService}）：
     * 研究完成后自动调用，也可 POST /deep/brief 手动重试。
     * <p>本服务保留状态机守护与事务边界（校验/抢占/推进/回退），认知产物落库由蓝图服务完成；
     * 项目状态机推到 READY（currentBriefId 指向该行），前端据此正常展示。
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
            // C3 认知产物:写作蓝图(thesis/论证结构/evidenceMap/质量信号) + blueprint_status=REVIEWING。
            // 蓝图服务内部「截断/空/非法 JSON → 提额 16384 重试一次」,两次均失败抛 AiException。
            ArticleBriefEntity updated = blueprintService.generate(projectId, briefId);

            // 条件更新:仅当仍处于本次抢占置的 GENERATING_BRIEF 才推进(并发已推进下游状态时不回退)
            statusService.advanceReady(projectId, briefId, java.util.Map.of());
            return updated;
        } catch (Exception e) {
            // 失败回 DRAFT 并记录原因；brief 行不动（无简报字段，前端保留深度面板可重试）。
            // 回退限定生成中状态:并发已推进(VERSIONS_READY 及之后)时不覆盖,保守安全。
            log.warn("深度简报生成失败 project={} brief={}: {}", projectId, briefId, e.getMessage(), e);
            statusService.failBriefToDraft(projectId, e.getMessage());
            throw new AiException("深度简报生成失败: " + e.getMessage(), e);
        }
    }
}
