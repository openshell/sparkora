package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.PromptTemplateLoader;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.web.dto.BydActivityGenerateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 王朝小程序「官方活动发文」生成服务(10-19-channel-editor-publish R4)。
 *
 * <p>与 {@link BydDynastyPushService} 职责分离:本服务只负责 AI 生成并落版本表,
 * 推送编排/Agent 契约完全不动。异步范式对齐 {@link VersionService}
 * ({@code start*}(同步校验+原子抢占) → {@code @Async run*}(自注入代理调用))。</p>
 *
 * <p>状态机复用 {@link ProjectStatusService}:小程序项目可从 DRAFT 直接生成,
 * 故使用允许 DRAFT 的 {@code claimDeepVersionsGenerating} 置 GENERATING_VERSIONS;
 * 成功后 {@code advanceVersionsReady} → VERSIONS_READY,失败 {@code failVersionsToReady}
 * 写 {@code last_version_error}(中文,状态服务内截断 1000)。</p>
 */
@Slf4j
@Service
public class BydDynastyGenerateService {

    private static final String CHANNEL_BYD_MINIAPP = "byd_miniapp";
    private static final int FIRST_MAX_TOKENS = 8192;
    private static final int RETRY_MAX_TOKENS = 16384;

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final AiClient aiClient;
    private final ProjectStatusService statusService;

    /** 自注入代理,确保 @Async 生效(本类内直接 this.runGenerate 不会走代理)。 */
    @Autowired
    @Lazy
    private BydDynastyGenerateService self;

    public BydDynastyGenerateService(ArticleProjectMapper projectMapper,
                                     ArticleVersionMapper versionMapper,
                                     AiClient aiClient,
                                     ProjectStatusService statusService) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.aiClient = aiClient;
        this.statusService = statusService;
    }

    /**
     * 启动活动发文生成(同步毫秒级):校验 + 原子抢占 GENERATING_VERSIONS 后立即返回,
     * 后台异步生成新版本;前端轮询 {@code generate-state}。
     *
     * @param userId 当前操作用户(预留审计/日志口径,不改变版本表结构)
     * @return 占位标记 {@code {started:true, status:"GENERATING_VERSIONS"}}
     */
    public Map<String, Object> startGenerate(Long projectId, BydActivityGenerateRequest req, Long userId) {
        if (req == null) throw new IllegalArgumentException("请求参数不能为空");
        if (isBlank(req.getActivityBrief())) throw new IllegalArgumentException("官方活动要求不能为空");
        if (isBlank(req.getPersonalInfo())) throw new IllegalArgumentException("我的情况不能为空");

        ArticleProjectEntity p = requireBydProject(projectId);
        // 并发防护:正在生成中(未过期)拒绝重复触发;陈旧状态由状态服务抢占分支自愈。
        if (statusService.stuckGenerating(p)) {
            throw new IllegalStateException("正在生成中，请稍候");
        }
        statusService.claimDeepVersionsGenerating(projectId, p, "生成小程序文章");

        log.info("小程序活动发文生成已触发 project={} user={}", projectId, userId);
        (self == null ? this : self).runGenerate(projectId, req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("status", "GENERATING_VERSIONS");
        return out;
    }

    /** 查询生成状态(三角色只读)。 */
    public Map<String, Object> state(Long projectId) {
        ArticleProjectEntity p = requireBydProject(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        // 生成中且未过期才算 generating;超过陈旧阈值返回 false,允许用户重新触发(与抢占自愈一致)。
        out.put("generating", statusService.stuckGenerating(p));
        out.put("lastError", p.getLastVersionError());
        out.put("currentVersionId", p.getCurrentVersionId());
        return out;
    }

    /**
     * 异步生成并落库(由 self 代理调用)。
     *
     * <p>每次生成都创建**新版本**,不覆盖用户已编辑内容;成功后 current_version_id 指向新版本
     * (小程序工作台始终编辑/推送最新产物),再推进会状态机到 VERSIONS_READY。</p>
     */
    @Async
    public void runGenerate(Long projectId, BydActivityGenerateRequest req) {
        try {
            String system = PromptTemplateLoader.render("byd/generate-system.st",
                    Map.of("schema", AiClient.jsonSchema(BydDynastyDraft.class)));
            String user = PromptTemplateLoader.render("byd/generate-user.st", Map.of(
                    "activityBrief", req.getActivityBrief(),
                    "personalInfo", req.getPersonalInfo()));

            AiClient.TypedResult<BydDynastyDraft> tr;
            try {
                tr = aiClient.structured(system, user, FIRST_MAX_TOKENS, BydDynastyDraft.class);
            } catch (Exception first) {
                log.warn("小程序活动发文首次生成失败,提额重试({}) project={}: {}",
                        RETRY_MAX_TOKENS, projectId, first.getMessage());
                tr = aiClient.structured(system,
                        user + "\n注意:上次输出失败(可能被 max_tokens 截断或不是合法 JSON),请只输出一个完整、合法的 JSON 对象。",
                        RETRY_MAX_TOKENS, BydDynastyDraft.class);
            }

            BydDynastyDraft draft = tr.entity();
            if (draft == null) throw new AiException("AI 未返回文章内容", null);
            String content = draft.content() == null ? "" : draft.content().trim();
            if (content.isBlank()) throw new AiException("生成正文为空（可能 max_tokens 不足被截断）", null);
            String title = resolveTitle(draft.title());

            ArticleVersionEntity v = new ArticleVersionEntity();
            v.setProjectId(projectId);
            v.setTitle(title);
            v.setContentMd(content);
            v.setVersionLabel("BYD");
            v.setAiModel(tr.chat().model());
            v.setTokenUsage(tr.chat().totalTokens());
            v.setRagStatus("NO_KNOWLEDGE");
            v.setWordCount(content.length());
            v.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(v);

            // 小程序始终编辑/推送最新生成版本:显式指向新版本(advanceVersionsReady 的「仅首版设值」不适用追加生成)。
            // 条件限定生成中状态,避免并发推进后把 current 改回旧链路。
            projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                    .eq("id", projectId)
                    .eq("status", "GENERATING_VERSIONS")
                    .set("current_version_id", v.getId())
                    .set("updated_at", LocalDateTime.now()));
            statusService.advanceVersionsReady(projectId, v.getId(), null);
            log.info("小程序活动发文生成完成 project={} version={} chars={}",
                    projectId, v.getId(), content.length());
        } catch (Exception e) {
            // 异步体无调用方,异常只落状态(中文,状态服务截断 1000)。
            log.warn("小程序活动发文生成失败 project={}: {}", projectId, e.getMessage(), e);
            statusService.failVersionsToReady(projectId, errorMessage(e));
        }
    }

    private ArticleProjectEntity requireBydProject(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("项目 id 不能为空");
        ArticleProjectEntity p = projectMapper.selectById(projectId);
        if (p == null) throw new IllegalArgumentException("项目不存在");
        if (!CHANNEL_BYD_MINIAPP.equals(p.getChannel())) {
            throw new IllegalArgumentException("该项目不是王朝小程序渠道项目");
        }
        return p;
    }

    private static String resolveTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isBlank()) return "未命名小程序文章";
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static String errorMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? "生成失败,请稍后重试" : msg;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * AI 结构化输出 DTO:title + content。
     * content 为纯文本正文(空行分段,不使用 Markdown 标题/表格/链接)。
     */
    public record BydDynastyDraft(String title, String content) {
    }
}
