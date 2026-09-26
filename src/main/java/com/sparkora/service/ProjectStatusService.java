package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 项目状态机唯一写权持有者(09-27-state-machine-service,契约见 docs/README.md 4.2)。
 *
 * <p>sparkora_article_project 的 status / last_brief_error / last_version_error / last_publish_error
 * 写入全部收敛到本服务。唯二例外:ArticleProjectController 创建时 INSERT 初始 DRAFT(非状态机转换)
 * 与 schema.sql 启动回填(存量数据修复,随 Flyway 子任务处置)。
 *
 * <p>历史教训:09-10-versions-page-fix(深度链路漏推状态机)与 09-27 P0-②(8 处 updateById 并发回写)
 * 都是状态推进逻辑散落多处的直接产物。
 *
 * <p>口径以 09-27-p0-hardening 修复后实现为基线,WHERE/SET 逐项等价:
 * 抢占带状态白名单+陈旧自愈(陈旧分支必须限定生成中状态);成功推进条件更新防回退;
 * 失败回退仅生成中状态;首版 current 两拆分;一律 UpdateWrapper 显式 set(不用 updateById 全字段回写)。
 *
 * <p>业务列不进本服务(如 imitation_analysis):调用方以 extraCols 参数传入同一条 UPDATE,保持原子性。
 */
@Slf4j
@Service
public class ProjectStatusService {

    /** 生成中状态超过该时长视为陈旧(JVM 中途死亡/重启残留),允许重新触发以自愈。唯一定义处。 */
    public static final long STALE_GENERATING_MS = 10 * 60 * 1000L;

    private final ArticleProjectMapper projectMapper;

    public ProjectStatusService(ArticleProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    /** 项目是否卡在生成中状态(未过期)。前端禁重复提交同款口径。 */
    public boolean stuckGenerating(ArticleProjectEntity p) {
        String s = p.getStatus();
        boolean generating = "GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s);
        if (!generating) return false;
        // updated_at 超过阈值 = 生成进程已不存在(正常生成最长 AI_TIMEOUT_MS 级别,10 分钟足够宽裕)
        return p.getUpdatedAt() != null
                && p.getUpdatedAt().isAfter(LocalDateTime.now().minus(Duration.ofMillis(STALE_GENERATING_MS)));
    }

    /** 409 守卫提示语:生成中提示稍候;已推进到下游状态说明该步已完成,重触发会把状态机拉回本步。 */
    public static String guardMsg(ArticleProjectEntity p, String action) {
        String s = p.getStatus();
        if ("GENERATING_BRIEF".equals(s) || "GENERATING_VERSIONS".equals(s))
            return "该项目正在生成中，请稍候（刷新页面可查看进度）";
        return "项目状态为「" + s + "」，" + action + "仅在对应前置状态可用；下游步骤已触发，不支持回退重做";
    }

    // ==================== 抢占(条件更新置生成中) ====================

    /**
     * 简报/仿写域原子抢占:仅 DRAFT/READY 或「生成中且已陈旧」置 GENERATING_BRIEF,清 last_brief_error。
     * claimed==0 抛 IllegalStateException(409 语义,提示语用调用方刚读的 p 快照)。
     */
    public void claimBriefGenerating(Long projectId, ArticleProjectEntity p, String action) {
        LocalDateTime staleCutoff = LocalDateTime.now().minus(Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "DRAFT", "READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_BRIEF")
                .set("last_brief_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(guardMsg(p, action));
    }

    /**
     * 版本域原子抢占:仅 READY/VERSIONS_READY(首生成或追加)或「生成中且已陈旧」置 GENERATING_VERSIONS,清 last_version_error。
     * 陈旧分支必须限定生成中状态,否则任何 updated_at 较旧的下游状态都会被误放行、状态机回退。
     * claimed==0 抛 IllegalStateException(409 语义)。
     */
    public void claimVersionsGenerating(Long projectId, ArticleProjectEntity p, String action) {
        LocalDateTime staleCutoff = LocalDateTime.now().minus(Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "READY", "VERSIONS_READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_VERSIONS")
                .set("last_version_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(guardMsg(p, action));
    }

    /**
     * 深度写作域原子抢占(09-27-gen-async):仅 READY/DRAFT/VERSIONS_READY 或「生成中且已陈旧」置 GENERATING_VERSIONS。
     * 与 {@link #claimVersionsGenerating} 的源态白名单不同,语义不同不合并:
     * 深度链路可从 DRAFT 触发(「跳过简报直接生成正文」,brief 侧 RESEARCH_DONE 但创作简报未生成),
     * 亦可从 READY(首次生成)或 VERSIONS_READY(追加)触发。claimed==0 抛 IllegalStateException(409 语义)。
     */
    public void claimDeepVersionsGenerating(Long projectId, ArticleProjectEntity p, String action) {
        LocalDateTime staleCutoff = LocalDateTime.now().minus(Duration.ofMillis(STALE_GENERATING_MS));
        int claimed = projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .and(w -> w.in("status", "READY", "DRAFT", "VERSIONS_READY")
                        .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                                .lt("updated_at", staleCutoff)))
                .set("status", "GENERATING_VERSIONS")
                .set("last_version_error", null)
                .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) throw new IllegalStateException(guardMsg(p, action));
    }

    // ==================== 成功推进(条件更新,防回退) ====================

    /**
     * 简报成功:仅 GENERATING_BRIEF → READY,set current_brief_id + 清 last_brief_error;
     * extraCols 业务列(如 imitation_analysis)同条 UPDATE 写入,保持原子性。
     */
    public void advanceReady(Long projectId, Long briefId, Map<String, Object> extraCols) {
        UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .eq("status", "GENERATING_BRIEF")
                .set("current_brief_id", briefId);
        if (extraCols != null) {
            for (Map.Entry<String, Object> e : extraCols.entrySet()) {
                uw.set(e.getKey(), e.getValue());
            }
        }
        uw.set("status", "READY")
                .set("last_brief_error", null)
                .set("updated_at", LocalDateTime.now());
        projectMapper.update(null, uw);
    }

    /**
     * 多版本成功:仅 GENERATING_VERSIONS → VERSIONS_READY(并发已改为 VERSIONS_READY/PUBLISHED_DRAFT 时不回退)。
     * 首版两拆分:current_version_id 保留「仅首版设值」语义——先条件 set(当前为 null),未命中则第二条只推进状态
     * 不覆盖用户已选 current;partialErrors 为部分失败明细,可空写入 last_version_error。
     */
    public void advanceVersionsReady(Long projectId, Long firstVersionId, String partialErrors) {
        LocalDateTime nowTs = LocalDateTime.now();
        int advanced = projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .eq("status", "GENERATING_VERSIONS")
                .isNull("current_version_id")
                .set("current_version_id", firstVersionId)
                .set("status", "VERSIONS_READY")
                .set("last_version_error", partialErrors)
                .set("updated_at", nowTs));
        if (advanced == 0) {
            projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .eq("status", "GENERATING_VERSIONS")
                    .set("status", "VERSIONS_READY")
                    .set("last_version_error", partialErrors)
                    .set("updated_at", nowTs));
        }
    }

    /**
     * 发布成功:任意态 → PUBLISHED_DRAFT(可重发覆盖),set media_id/theme/published_at,清 last_publish_error。
     * publishedAt 由调用方传入(同一时间戳同时用于响应体,保证对外一致)。
     */
    public void markPublished(Long projectId, String mediaId, String theme, LocalDateTime publishedAt) {
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("status", "PUBLISHED_DRAFT")
                .set("publish_media_id", mediaId)
                .set("publish_theme", theme)
                .set("published_at", publishedAt)
                .set("last_publish_error", null)
                .set("updated_at", publishedAt));
    }

    // ==================== 失败回退(仅生成中状态,防覆盖并发推进) ====================

    /** 简报/仿写失败:仅 GENERATING_BRIEF/GENERATING_VERSIONS → DRAFT,写 last_brief_error(截断 1000 防超列)。 */
    public void failBriefToDraft(Long projectId, String reason) {
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                .set("status", "DRAFT")
                .set("last_brief_error", truncate(reason, 1000))
                .set("updated_at", LocalDateTime.now()));
    }

    /** 版本失败:仅 GENERATING_VERSIONS/GENERATING_BRIEF → READY,写 last_version_error(截断 1000 防超列)。 */
    public void failVersionsToReady(Long projectId, String reason) {
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .in("status", "GENERATING_VERSIONS", "GENERATING_BRIEF")
                .set("status", "READY")
                .set("last_version_error", truncate(reason, 1000))
                .set("updated_at", LocalDateTime.now()));
    }

    /**
     * 发布失败:不动状态(可重试),仅写 last_publish_error——压缩空白+截断 990(原 PublishService.markFailure 口径),
     * 后端日志留完整错误;落库失败仅 warn 不抛(失败记录本身不能阻断调用方)。
     * 完整逻辑体自 PublishService 吸收(09-27-state-machine-service),PublishService.markFailure 为纯委托门面。
     */
    public void markPublishFailure(Long projectId, String message) {
        String msg = message == null ? "未知错误" : message.replaceAll("\\s+", " ").trim();
        if (msg.length() > 990) msg = msg.substring(0, 990) + "…";
        log.error("发布失败 project={} 原因: {}", projectId, msg);
        try {
            projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .set("last_publish_error", msg)
                    .set("updated_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("发布失败原因落库失败 project={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 单列错误列写入/清空(ClarifyService 异步清错/写错场景,不触碰状态)。
     * reasonOrNull=null 清空(仅当已有错误才发 UPDATE,避免无谓刷新 updated_at);非 null 截断 1000 写入。
     */
    public void writeBriefError(Long projectId, String reasonOrNull) {
        UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId);
        if (reasonOrNull == null) {
            uw.isNotNull("last_brief_error").set("last_brief_error", null);
        } else {
            uw.set("last_brief_error", truncate(reasonOrNull, 1000));
        }
        uw.set("updated_at", LocalDateTime.now());
        projectMapper.update(null, uw);
    }

    /** 截断(超长截 max 字符,防超列);null 安全。 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }
}