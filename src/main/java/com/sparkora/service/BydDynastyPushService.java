package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.BydDynastyProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.BydDynastyAgentEntity;
import com.sparkora.domain.entity.BydDynastyMediaEntity;
import com.sparkora.domain.entity.BydDynastyPushJobEntity;
import com.sparkora.mapper.ArticleProjectMapper;
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

import java.net.URI;
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
 * <p>安全边界：payload 只包含文章内容和受控媒体引用，禁止携带本机 MMKV 路径、MMKV 加密材料
 * 或任意命令。Agent 只能领取绑定用户的任务，且必须通过任务状态原子更新领取。</p>
 */
@Slf4j
@Service
public class BydDynastyPushService {

    private static final int PAYLOAD_VERSION = 2;
    private static final String CHANNEL_BYD_MINIAPP = "byd_miniapp";
    private static final int MAX_ERROR = 990;

    private final ArticleProjectMapper projectMapper;
    private final ArticleVersionMapper versionMapper;
    private final BydDynastyMediaService mediaService;
    private final BydDynastyAgentMapper agentMapper;
    private final BydDynastyPushJobMapper jobMapper;
    private final BydDynastyProperties props;
    private final ObjectMapper json;

    public BydDynastyPushService(ArticleProjectMapper projectMapper,
                                 ArticleVersionMapper versionMapper,
                                 BydDynastyMediaService mediaService,
                                 BydDynastyAgentMapper agentMapper,
                                 BydDynastyPushJobMapper jobMapper,
                                 BydDynastyProperties props,
                                 ObjectMapper json) {
        this.projectMapper = projectMapper;
        this.versionMapper = versionMapper;
        this.mediaService = mediaService;
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
        ArticleProjectEntity project = requireBydProject(projectId, false);
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

        ArticleProjectEntity project = requireBydProject(projectId, true);
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
        BuiltPayload built = buildPayload(jobId, projectId, version.getId(), draftId,
                version.getTitle(), version.getContentMd(), clubId, clubTitle);
        Map<String, Object> payload = built.payload();

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
        requireBydProject(projectId, false);
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

    private BuiltPayload buildPayload(String jobId, Long projectId, Long versionId, String draftId,
                                             String title, String contentMd, String clubId, String clubTitle) {
        List<BydDynastyPlainTextRenderer.Block> blocks = BydDynastyPlainTextRenderer.compile(contentMd);
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> media = new ArrayList<>();
        Map<String, Integer> mediaRefs = new LinkedHashMap<>();
        Map<String, Integer> legacyRefs = new LinkedHashMap<>();
        Map<String, BydDynastyMediaEntity> referenced = new LinkedHashMap<>();
        int videoCount = 0;

        for (BydDynastyPlainTextRenderer.Block block : blocks) {
            if (block instanceof BydDynastyPlainTextRenderer.TextBlock text) {
                messages.add(Map.of("type", "text", "content", text.text()));
                continue;
            }
            if (block instanceof BydDynastyPlainTextRenderer.ImageBlock image) {
                String target = image.target();
                int ref;
                if (isHttpUrl(target)) {
                    ref = legacyRefs.computeIfAbsent(target, ignored -> {
                        int id = media.size() + 1;
                        media.add(legacyImageMedia("MEDIA-" + id, target));
                        return id;
                    });
                } else {
                    BydDynastyMediaEntity row = mediaService.requireReadyForPush(
                            projectId, target, BydDynastyMediaService.TYPE_IMAGE);
                    referenced.putIfAbsent(target, row);
                    Integer existing = mediaRefs.get(target);
                    if (existing == null) {
                        existing = media.size() + 1;
                        mediaRefs.put(target, existing);
                        media.add(stagedMediaPayload("MEDIA-" + existing, row, jobId));
                    }
                    ref = existing;
                }
                messages.add(Map.of("type", "img", "mediaRef", "MEDIA-" + ref));
                continue;
            }
            if (block instanceof BydDynastyPlainTextRenderer.VideoBlock video) {
                videoCount++;
                int maxVideos = Math.max(0, props.getMediaMaxVideos());
                if (videoCount > maxVideos) {
                    throw new IllegalArgumentException("小程序文章最多支持 " + maxVideos + " 个视频");
                }
                String target = video.target();
                BydDynastyMediaEntity row = mediaService.requireReadyForPush(
                        projectId, target, BydDynastyMediaService.TYPE_VIDEO);
                referenced.putIfAbsent(target, row);
                Integer ref = mediaRefs.get(target);
                if (ref == null) {
                    ref = media.size() + 1;
                    mediaRefs.put(target, ref);
                    media.add(stagedMediaPayload("MEDIA-" + ref, row, jobId));
                }
                messages.add(Map.of("type", "video", "mediaRef", "MEDIA-" + ref));
            }
        }
        if (messages.isEmpty()) throw new IllegalStateException("正文内容为空，无法编译 BYD 王朝草稿");

        // 只延长实际被 payload 引用的媒体；事务回滚时数据库与任务插入一起回退。
        mediaService.extendLifetimeForJob(new ArrayList<>(referenced.values()), LocalDateTime.now());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payloadVersion", PAYLOAD_VERSION);
        payload.put("jobId", jobId);
        payload.put("projectId", projectId);
        payload.put("versionId", versionId);
        payload.put("draftId", draftId);
        String cleanTitle = BydDynastyPlainTextRenderer.renderInline(title);
        payload.put("title", cleanTitle.isBlank() ? "无标题" : cleanTitle);
        payload.put("messages", messages);
        payload.put("media", media);
        payload.put("clubId", blankToNull(clubId));
        payload.put("clubTitle", blankToNull(clubTitle));
        return new BuiltPayload(payload);
    }

    private Map<String, Object> stagedMediaPayload(String ref, BydDynastyMediaEntity row, String jobId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ref);
        item.put("mediaId", row.getMediaId());
        item.put("type", BydDynastyMediaService.TYPE_VIDEO.equals(row.getMediaType()) ? "video" : "image");
        item.put("sourceType", row.getSourceKind());
        if (BydDynastyMediaService.SOURCE_STAGED.equals(row.getSourceKind())) {
            item.put("downloadPath", "/api/agent/byd-dynasty/jobs/" + jobId + "/media/" + row.getMediaId());
        } else if (BydDynastyMediaService.SOURCE_URL.equals(row.getSourceKind())) {
            item.put("url", row.getSourceUrl());
        } else {
            throw new IllegalStateException("正文媒体来源类型不合法");
        }
        item.put("fileName", row.getFileName());
        item.put("mimeType", row.getMimeType());
        item.put("byteSize", row.getByteSize());
        item.put("width", row.getWidth());
        item.put("height", row.getHeight());
        if (row.getDurationSeconds() != null) item.put("durationSeconds", row.getDurationSeconds());
        return item;
    }

    /** 历史正文中的 http(s) 图片仅作兼容入口，最终仍由 Agent 下载并上传 BYD CDN。 */
    private Map<String, Object> legacyImageMedia(String ref, String url) {
        if (!isHttpUrl(url)) throw new IllegalArgumentException("正文图片地址不是有效的 http(s) URL");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ref);
        item.put("type", "image");
        item.put("sourceType", BydDynastyMediaService.SOURCE_URL);
        item.put("url", url);
        item.put("fileName", legacyFileName(url));
        item.put("mimeType", legacyImageMime(url));
        item.put("byteSize", 0L);
        return item;
    }

    private String legacyFileName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) return name.length() <= 255 ? name : name.substring(name.length() - 255);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return "legacy-image";
    }

    private String legacyImageMime(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
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

    private ArticleProjectEntity requireBydProject(Long projectId, boolean requirePublishable) {
        if (projectId == null) throw new IllegalArgumentException("项目 id 不能为空");
        ArticleProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        if (!CHANNEL_BYD_MINIAPP.equals(project.getChannel())) {
            throw new IllegalArgumentException("仅比亚迪王朝小程序项目可执行该操作");
        }
        if (requirePublishable && project.getCurrentVersionId() == null) {
            throw new IllegalStateException("尚未选定正文版本，无法创建 BYD 推送任务");
        }
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

    private record BuiltPayload(Map<String, Object> payload) {
    }
}
