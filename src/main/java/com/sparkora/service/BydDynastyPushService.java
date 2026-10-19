package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.BydDynastyProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.BydDynastyAgentEntity;
import com.sparkora.domain.entity.BydDynastyPushJobEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionImageMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.BydDynastyAgentMapper;
import com.sparkora.mapper.BydDynastyPushJobMapper;
import com.sparkora.web.dto.BydAgentClaimRequest;
import com.sparkora.web.dto.BydAgentHeartbeatRequest;
import com.sparkora.web.dto.BydAgentRegisterRequest;
import com.sparkora.web.dto.BydAgentResultRequest;
import com.sparkora.web.dto.BydPushJobCreateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BYD 王朝小程序本地推送编排服务。
 *
 * <p>Sparkora Server 只负责权限、任务契约、状态流转和审计；MMKV 环境检测、备份、BYD 图片
 * 上传与草稿写入全部由本机 Agent 执行。这样既避免服务器直写用户本机微信文件，也不把
 * MMKV 逆向逻辑引入服务端。</p>
 *
 * <p>安全边界：payload 只包含文章内容与可公网访问的图片源 URL，禁止携带本机 MMKV 路径、
 * MMKV 加密材料或任意命令。Agent 只能领取绑定用户的任务，且必须通过任务状态原子更新领取。</p>
 */
@Slf4j
@Service
public class BydDynastyPushService {

    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_ERROR = 990;

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final ArticleVersionImageMapper versionImageMapper;
    private final ImageService imageService;
    private final BydDynastyAgentMapper agentMapper;
    private final BydDynastyPushJobMapper jobMapper;
    private final BydDynastyProperties props;
    private final ObjectMapper json;

    public BydDynastyPushService(ArticleProjectMapper projectMapper,
                                 ArticleVersionMapper versionMapper,
                                 ArticleVersionImageMapper versionImageMapper,
                                 ImageService imageService,
                                 BydDynastyAgentMapper agentMapper,
                                 BydDynastyPushJobMapper jobMapper,
                                 BydDynastyProperties props,
                                 ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.versionImageMapper = versionImageMapper;
        this.imageService = imageService;
        this.agentMapper = agentMapper;
        this.jobMapper = jobMapper;
        this.props = props;
        this.json = json;
    }

    // ==================== Agent 专属鉴权 ====================

    /** Agent API 已在 SecurityConfig 放行，这里用专属 Token 二次鉴权，避免与用户 JWT 混用。 */
    public boolean agentAuthorized(String token) {
        if (token == null || token.isBlank() || props.getAgentToken() == null || props.getAgentToken().isBlank())
            return false;
        byte[] provided = token.getBytes(StandardCharsets.UTF_8);
        byte[] expected = props.getAgentToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    // ==================== Agent 注册 / 心跳 ====================

    @Transactional
    public Map<String, Object> registerAgent(BydAgentRegisterRequest req) {
        requireAgentConfig();
        String agentId = normalize(req.getAgentId(), "Agent ID");
        if (agentId.length() > 64) throw new IllegalArgumentException("Agent ID 长度不能超过 64");

        BydDynastyAgentEntity row = agentMapper.selectOne(new QueryWrapper<BydDynastyAgentEntity>().eq("agent_id", agentId));
        LocalDateTime now = LocalDateTime.now();
        String preflightJson = serializeJson(req.getPreflight());
        if (row == null) {
            row = new BydDynastyAgentEntity();
            row.setOwnerUserId(props.getAgentOwnerUserId());
            row.setAgentId(agentId);
            row.setStatus("ONLINE");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setDeleted(0);
        } else {
            row.setUpdatedAt(now);
        }
        row.setOwnerUserId(props.getAgentOwnerUserId());
        row.setMachineName(normalizeOrNull(req.getMachineName()));
        row.setOsName(normalizeOrNull(req.getOsName()));
        row.setAgentVersion(normalizeOrNull(req.getAgentVersion()));
        row.setStatus("ONLINE");
        row.setLastSeenAt(now);
        row.setLastPreflightJson(preflightJson);
        if (row.getId() == null) agentMapper.insert(row);
        else agentMapper.updateById(row);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentRowId", row.getId());
        data.put("agentId", row.getAgentId());
        data.put("ownerUserId", row.getOwnerUserId());
        data.put("status", row.getStatus());
        data.put("lastSeenAt", row.getLastSeenAt());
        data.put("preflightReady", preflightReady(preflightJson));
        return data;
    }

    @Transactional
    public Map<String, Object> heartbeat(BydAgentHeartbeatRequest req) {
        requireAgentConfig();
        String agentId = normalize(req.getAgentId(), "Agent ID");
        BydDynastyAgentEntity row = agentMapper.selectOne(new QueryWrapper<BydDynastyAgentEntity>().eq("agent_id", agentId));
        if (row == null) throw new IllegalStateException("BYD 本地 Agent 未注册，请先调用注册接口");
        LocalDateTime now = LocalDateTime.now();
        String preflightJson = serializeJson(req.getPreflight());
        row.setMachineName(normalizeOrNull(req.getMachineName()));
        row.setOsName(normalizeOrNull(req.getOsName()));
        row.setAgentVersion(normalizeOrNull(req.getAgentVersion()));
        row.setStatus("ONLINE");
        row.setLastSeenAt(now);
        row.setLastPreflightJson(preflightJson);
        row.setUpdatedAt(now);
        agentMapper.updateById(row);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", row.getAgentId());
        data.put("status", "ONLINE");
        data.put("lastSeenAt", now);
        data.put("preflightReady", preflightReady(preflightJson));
        return data;
    }

    // ==================== Agent 领取 / 回传 ====================

    @Transactional
    public Map<String, Object> claimJob(BydAgentClaimRequest req) {
        requireAgentConfig();
        String agentId = normalize(req.getAgentId(), "Agent ID");
        BydDynastyAgentEntity agent = agentMapper.selectOne(new QueryWrapper<BydDynastyAgentEntity>().eq("agent_id", agentId));
        if (agent == null) throw new IllegalStateException("BYD 本地 Agent 未注册，请先调用注册接口");

        markStaleAgentsOffline();

        QueryWrapper<BydDynastyPushJobEntity> qw = new QueryWrapper<BydDynastyPushJobEntity>()
                .eq("status", "PENDING")
                .eq("created_by", agent.getOwnerUserId())
                .orderByAsc("created_at", "id")
                .last("LIMIT 20");
        for (BydDynastyPushJobEntity candidate : jobMapper.selectList(qw)) {
            LocalDateTime now = LocalDateTime.now();
            int claimed = jobMapper.update(null, new UpdateWrapper<BydDynastyPushJobEntity>()
                    .eq("id", candidate.getId())
                    .eq("status", "PENDING")
                    .isNull("agent_id")
                    .set("agent_id", agent.getId())
                    .set("status", "RUNNING")
                    .set("claimed_at", now)
                    .set("started_at", now)
                    .set("updated_at", now));
            if (claimed > 0) {
                BydDynastyPushJobEntity job = jobMapper.selectById(candidate.getId());
                return Map.of("claimed", Boolean.TRUE, "job", job == null ? Map.of() : job);
            }
        }
        return Map.of("claimed", Boolean.FALSE, "reason", "当前没有可领取的任务");
    }

    @Transactional
    public Map<String, Object> reportResult(String jobId, BydAgentResultRequest req) {
        requireAgentConfig();
        String agentId = normalize(req.getAgentId(), "Agent ID");
        BydDynastyAgentEntity agent = agentMapper.selectOne(new QueryWrapper<BydDynastyAgentEntity>().eq("agent_id", agentId));
        if (agent == null) throw new IllegalStateException("BYD 本地 Agent 未注册，请先调用注册接口");
        if (req.getSuccess() == null) throw new IllegalArgumentException("执行结果必须包含 success");

        BydDynastyPushJobEntity job = jobMapper.selectOne(new QueryWrapper<BydDynastyPushJobEntity>().eq("job_id", safe(jobId)));
        if (job == null) throw new IllegalArgumentException("推送任务不存在");
        if (!agent.getId().equals(job.getAgentId())) throw new IllegalStateException("该任务由其他 Agent 领取，不允许重复上报");

        LocalDateTime now = LocalDateTime.now();
        String resultJson = serializeJson(req);
        String finalStatus = Boolean.TRUE.equals(req.getSuccess()) ? "SUCCESS" : "FAILED";
        int updated;
        if (Boolean.TRUE.equals(req.getSuccess())) {
            updated = jobMapper.update(null, new UpdateWrapper<BydDynastyPushJobEntity>()
                    .eq("id", job.getId())
                    .eq("status", "RUNNING")
                    .eq("agent_id", agent.getId())
                    .set("status", finalStatus)
                    .set("result_json", resultJson)
                    .set("finished_at", now)
                    .set("updated_at", now));
        } else {
            updated = jobMapper.update(null, new UpdateWrapper<BydDynastyPushJobEntity>()
                    .eq("id", job.getId())
                    .eq("status", "RUNNING")
                    .eq("agent_id", agent.getId())
                    .set("status", finalStatus)
                    .set("result_json", resultJson)
                    .set("error_message", truncate(blankToNull(req.getError()) == null ? "本机 Agent 执行失败" : req.getError()))
                    .set("finished_at", now)
                    .set("updated_at", now));
        }
        if (updated == 0) throw new IllegalStateException("推送任务已结束或状态不一致，不能重复上报");

        if (Boolean.TRUE.equals(req.getSuccess())) {
            applySuccessToProject(job, req);
        } else {
            writeBydError(job.getProjectId(), req.getError() == null || req.getError().isBlank() ? "本机 Agent 执行失败" : req.getError());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getJobId());
        data.put("status", finalStatus);
        data.put("finishedAt", now);
        return data;
    }

    // ==================== 用户侧任务 ====================

    public Map<String, Object> state(Long projectId, Long currentUserId) {
        ArticleProjectEntity project = requireProject(projectId, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", project.getId());
        data.put("agentConfigured", props.agentConfigured());
        boolean configured = props.agentConfigured();
        if (configured) markStaleAgentsOffline();

        Long ownerUserId = currentUserId != null ? currentUserId : toLongQuietly(project.getCreatedBy());
        BydDynastyAgentEntity agent = currentOnlineAgent(ownerUserId);
        data.put("agentOnline", agent != null);
        data.put("agentId", agent == null ? null : agent.getAgentId());
        data.put("agentMachineName", agent == null ? null : agent.getMachineName());
        data.put("agentVersion", agent == null ? null : agent.getAgentVersion());
        data.put("agentPreflightReady", agent != null && preflightReady(agent.getLastPreflightJson()));
        data.put("agentPreflight", agent == null || agent.getLastPreflightJson() == null ? null : parseJson(agent.getLastPreflightJson()));
        data.put("agentDisabledReason", disabledReason());

        data.put("clubId", project.getBydDynastyClubId());
        data.put("clubTitle", project.getBydDynastyClubTitle());
        data.put("publishedAt", project.getBydDynastyPublishedAt());
        data.put("lastJobId", project.getBydDynastyLastJobId());
        data.put("lastDraftId", project.getBydDynastyLastDraftId());
        data.put("lastDraftCount", project.getBydDynastyLastDraftCount());
        data.put("lastError", project.getBydDynastyLastError());

        BydDynastyPushJobEntity latest = latestJob(projectId, null);
        data.put("latestJob", latest == null ? null : jobMap(latest));
        return data;
    }

    @Transactional
    public Map<String, Object> createJob(Long projectId, BydPushJobCreateRequest req, Long createdBy) {
        if (createdBy == null) throw new IllegalStateException("未登录或缺少用户 id");
        if (!props.agentConfigured()) throw new IllegalStateException(disabledReason());

        ArticleProjectEntity project = requireProject(projectId, true);
        ArticleVersionEntity version = requireVersion(project);
        markStaleAgentsOffline();
        BydDynastyAgentEntity agent = currentOnlineAgent(createdBy);
        if (agent == null) throw new IllegalStateException("BYD 本地 Agent 未注册或离线，请先在本机启动 Agent 并完成注册");
        if (!preflightReady(agent.getLastPreflightJson()))
            throw new IllegalStateException("BYD 本地 Agent 预检未通过，请检查 Windows 本机环境、微信小程序目录与 MMKV 文件后重新心跳");

        String jobId = uuid32();
        String draftId = uuid32();
        String clubId = firstNonBlank(req == null ? null : req.getClubId(), project.getBydDynastyClubId(), props.getDefaultClubId());
        String clubTitle = firstNonBlank(req == null ? null : req.getClubTitle(), project.getBydDynastyClubTitle(), props.getDefaultClubTitle());
        Map<String, Object> payload = buildPayload(jobId, projectId, version.getId(), draftId,
                version.getTitle(), version.getContentMd(), clubId, clubTitle, version.getCoverImageId());

        BydDynastyPushJobEntity job = new BydDynastyPushJobEntity();
        job.setJobId(jobId);
        job.setProjectId(projectId);
        job.setVersionId(version.getId());
        job.setAgentId(null);
        job.setStatus("PENDING");
        job.setPayloadJson(serializeJson(payload));
        job.setCreatedBy(createdBy);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(job.getCreatedAt());
        if (jobMapper.insert(job) != 1) throw new IllegalStateException("创建 BYD 推送任务失败，请稍后重试");

        LocalDateTime now = LocalDateTime.now();
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("byd_dynasty_last_job_id", jobId)
                .set("updated_at", now));

        Map<String, Object> data = jobMap(job);
        data.put("payload", payload);
        return data;
    }

    public Map<String, Object> getJob(Long projectId, String jobId) {
        requireProject(projectId, false);
        BydDynastyPushJobEntity job = jobMapper.selectOne(new QueryWrapper<BydDynastyPushJobEntity>()
                .eq("project_id", projectId)
                .eq("job_id", safe(jobId)));
        if (job == null) throw new IllegalArgumentException("推送任务不存在");
        return jobMap(job);
    }

    // ==================== 工具方法 ====================

    private void applySuccessToProject(BydDynastyPushJobEntity job, BydAgentResultRequest req) {
        UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", job.getProjectId())
                .set("byd_dynasty_published_at", LocalDateTime.now())
                .set("byd_dynasty_last_job_id", job.getJobId())
                .set("byd_dynasty_last_error", null)
                .set("updated_at", LocalDateTime.now());
        if (req.getDraftId() != null && !req.getDraftId().isBlank()) uw.set("byd_dynasty_last_draft_id", req.getDraftId());
        if (req.getDraftCount() != null) uw.set("byd_dynasty_last_draft_count", req.getDraftCount());
        // club 字段在任务创建时不落项目，改为只在成功后回写，避免失败任务污染选择。
        projectMapper.update(null, uw);
    }

    private void writeBydError(Long projectId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
                .eq("id", projectId)
                .set("byd_dynasty_last_error", truncate(reason))
                .set("updated_at", now));
    }

    private Map<String, Object> jobMap(BydDynastyPushJobEntity job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", job.getId());
        data.put("jobId", job.getJobId());
        data.put("projectId", job.getProjectId());
        data.put("versionId", job.getVersionId());
        data.put("agentId", job.getAgentId());
        data.put("status", job.getStatus());
        data.put("payload", job.getPayloadJson() == null ? null : parseJson(job.getPayloadJson()));
        data.put("result", job.getResultJson() == null ? null : parseJson(job.getResultJson()));
        data.put("errorMessage", job.getErrorMessage());
        data.put("createdBy", job.getCreatedBy());
        data.put("claimedAt", job.getClaimedAt());
        data.put("startedAt", job.getStartedAt());
        data.put("finishedAt", job.getFinishedAt());
        data.put("createdAt", job.getCreatedAt());
        data.put("updatedAt", job.getUpdatedAt());
        return data;
    }

    private Map<String, Object> buildPayload(String jobId, Long projectId, Long versionId, String draftId,
                                             String title, String contentMd, String clubId, String clubTitle,
                                             Long coverImageId) {
        SafeBody body = compileBody(contentMd);
        List<Map<String, Object>> images = new ArrayList<>();

        if (coverImageId != null) {
            String coverUrl = imageService.publicUrl(coverImageId);
            String ref = "COVER-" + coverImageId;
            images.add(imageSource(ref, coverUrl, "COVER"));
            if (!body.imageRefs.contains(ref)) body.messages.add(0, Map.of("type", "img", "imageRef", ref));
        }

        int sequence = 0;
        for (String sourceUrl : body.inlineImageUrls) {
            String ref = "BODY-" + (++sequence);
            images.add(imageSource(ref, sourceUrl, "BODY"));
        }
        // 渲染器产出的 BODY-n 引用与 payload.images 中同 ref 的图片来源一一对应。

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payloadVersion", PAYLOAD_VERSION);
        payload.put("jobId", jobId);
        payload.put("projectId", projectId);
        payload.put("versionId", versionId);
        payload.put("draftId", draftId);
        String cleanTitle = BydDynastyPlainTextRenderer.renderInline(title);
        payload.put("title", cleanTitle.isBlank() ? "无标题" : cleanTitle);
        payload.put("messages", body.messages);
        payload.put("images", images);
        payload.put("clubId", blankToNull(clubId));
        payload.put("clubTitle", blankToNull(clubTitle));
        return payload;
    }

    /** 把纯文本渲染器的有序块编译成 Agent 认识的 text/img 消息。 */
    private SafeBody compileBody(String contentMd) {
        SafeBody body = new SafeBody();
        int sequence = 0;
        for (BydDynastyPlainTextRenderer.Block block : BydDynastyPlainTextRenderer.compile(contentMd)) {
            if (block instanceof BydDynastyPlainTextRenderer.TextBlock text) {
                body.messages.add(Map.of("type", "text", "content", text.text()));
            } else if (block instanceof BydDynastyPlainTextRenderer.ImageBlock image) {
                String ref = "BODY-" + (++sequence);
                body.inlineImageUrls.add(image.url());
                body.imageRefs.add(ref);
                body.messages.add(Map.of("type", "img", "imageRef", ref));
            }
        }
        if (body.messages.isEmpty()) throw new IllegalStateException("正文内容为空，无法编译 BYD 王朝草稿");
        return body;
    }

    private Map<String, Object> imageSource(String ref, String url, String role) {
        if (url == null || url.isBlank()) throw new IllegalStateException("图片未转存图床，无法交给本机 Agent 上传");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", ref);
        source.put("sourceType", "URL");
        source.put("url", url);
        source.put("role", role);
        return source;
    }

    private BydDynastyAgentEntity currentOnlineAgent(Long ownerUserId) {
        if (ownerUserId == null) return null;
        return agentMapper.selectOne(new QueryWrapper<BydDynastyAgentEntity>()
                .eq("owner_user_id", ownerUserId)
                .eq("status", "ONLINE")
                .isNotNull("last_seen_at")
                .orderByDesc("last_seen_at", "id")
                .last("LIMIT 1"));
    }

    private void markStaleAgentsOffline() {
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(Math.max(1000, props.getHeartbeatTimeoutMs()) * 1_000_000L);
        var rows = agentMapper.selectList(new QueryWrapper<BydDynastyAgentEntity>()
                .eq("status", "ONLINE")
                .le("last_seen_at", cutoff));
        for (BydDynastyAgentEntity row : rows) {
            agentMapper.update(null, new UpdateWrapper<BydDynastyAgentEntity>()
                    .eq("id", row.getId())
                    .eq("status", "ONLINE")
                    .set("status", "OFFLINE")
                    .set("updated_at", LocalDateTime.now()));
        }
    }

    private ArticleProjectEntity requireProject(Long projectId, boolean requirePublishable) {
        if (projectId == null) throw new IllegalArgumentException("项目 id 不能为空");
        ArticleProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        if (!requirePublishable) return project;
        if (project.getCurrentVersionId() == null) throw new IllegalStateException("尚未选定正文版本，无法创建 BYD 推送任务");
        String status = project.getStatus();
        if (!"VERSIONS_READY".equals(status) && !"PUBLISHED_DRAFT".equals(status))
            throw new IllegalStateException("项目状态为 " + status + "，版本就绪后才可创建 BYD 推送任务");
        return project;
    }

    private ArticleVersionEntity requireVersion(ArticleProjectEntity project) {
        ArticleVersionEntity version = versionMapper.selectById(project.getCurrentVersionId());
        if (version == null) throw new IllegalStateException("当前版本不存在，请重新选定");
        if (version.getContentMd() == null || version.getContentMd().isBlank())
            throw new IllegalStateException("当前版本正文为空，无法创建 BYD 推送任务");
        return version;
    }

    private BydDynastyPushJobEntity latestJob(Long projectId, String jobId) {
        QueryWrapper<BydDynastyPushJobEntity> qw = new QueryWrapper<BydDynastyPushJobEntity>().eq("project_id", projectId);
        if (jobId != null && !jobId.isBlank()) qw.eq("job_id", jobId);
        qw.orderByDesc("created_at", "id").last("LIMIT 1");
        return jobMapper.selectList(qw).stream().findFirst().orElse(null);
    }

    private void requireAgentConfig() {
        if (!props.agentConfigured())
            throw new IllegalStateException("BYD Agent 配置缺失，请设置 BYD_DYNASTY_AGENT_TOKEN 与 BYD_DYNASTY_AGENT_OWNER_USER_ID");
    }

    private String disabledReason() {
        if (props.getAgentToken() == null || props.getAgentToken().isBlank())
            return "BYD Agent 访问密钥未配置（BYD_DYNASTY_AGENT_TOKEN）";
        if (props.getAgentOwnerUserId() == null)
            return "BYD Agent 绑定用户未配置（BYD_DYNASTY_AGENT_OWNER_USER_ID）";
        return null;
    }

    private boolean preflightReady(String preflightJson) {
        if (preflightJson == null || preflightJson.isBlank()) return false;
        try {
            JsonNode node = json.readTree(preflightJson);
            if (node.path("ready").isBoolean()) return node.path("ready").asBoolean();
            if (node.path("ok").isBoolean()) return node.path("ok").asBoolean();
            return false;
        } catch (Exception e) {
            log.warn("解析 Agent 预检 JSON 失败: {}", e.getMessage());
            return false;
        }
    }

    private Object parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readTree(value);
        } catch (Exception e) {
            return value;
        }
    }

    private String serializeJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private String normalize(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    private String normalizeOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private Long toLongQuietly(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String truncate(String value) {
        String msg = value == null ? "未知错误" : value.replaceAll("\\s+", " ").trim();
        return msg.length() <= MAX_ERROR ? msg : msg.substring(0, MAX_ERROR) + "…";
    }

    /** 服务内部临时集合容器，避免多输出参数暴露到公共类型。 */
    private static final class SafeBody {
        private final ArrayList<Map<String, Object>> messages = new ArrayList<>();
        private final ArrayList<String> inlineImageUrls = new ArrayList<>();
        private final ArrayList<String> imageRefs = new ArrayList<>();
    }
}
