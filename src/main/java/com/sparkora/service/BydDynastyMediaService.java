package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.BydDynastyProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.BydDynastyAgentEntity;
import com.sparkora.domain.entity.BydDynastyMediaEntity;
import com.sparkora.domain.entity.BydDynastyPushJobEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.BydDynastyAgentMapper;
import com.sparkora.mapper.BydDynastyMediaMapper;
import com.sparkora.mapper.BydDynastyPushJobMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.storage.ImageStorage;
import com.sparkora.web.dto.BydMediaFromImageRequest;
import com.sparkora.web.dto.BydMediaReservationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BYD 小程序正文媒体暂存。
 *
 * <p>文件只在服务器数据卷内短暂落盘，前端拿到的只是预留/状态元数据；Agent 必须凭任务范围
 * 地址下载。上传和下载都使用流式 I/O，300MB 视频不会整体进入 JVM 内存。</p>
 */
@Slf4j
@Service
public class BydDynastyMediaService {

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String SOURCE_STAGED = "STAGED";
    public static final String SOURCE_URL = "URL";
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";

    private static final String CHANNEL_BYD_MINIAPP = "byd_miniapp";
    private static final long COPY_BUFFER = 64 * 1024L;

    private final BydDynastyMediaMapper mediaMapper;
    private final ArticleProjectMapper projectMapper;
    private final ImageAssetMapper imageMapper;
    private final BydDynastyPushJobMapper jobMapper;
    private final BydDynastyAgentMapper agentMapper;
    private final ImageStorage imageStorage;
    private final BydDynastyProperties props;
    private final ObjectMapper json;

    public BydDynastyMediaService(BydDynastyMediaMapper mediaMapper,
                                  ArticleProjectMapper projectMapper,
                                  ImageAssetMapper imageMapper,
                                  BydDynastyPushJobMapper jobMapper,
                                  BydDynastyAgentMapper agentMapper,
                                  ImageStorage imageStorage,
                                  BydDynastyProperties props,
                                  ObjectMapper json) {
        this.mediaMapper = mediaMapper;
        this.projectMapper = projectMapper;
        this.imageMapper = imageMapper;
        this.jobMapper = jobMapper;
        this.agentMapper = agentMapper;
        this.imageStorage = imageStorage;
        this.props = props;
        this.json = json;
    }

    /** 预留媒体元数据；此时只落 PENDING 记录，不创建文件。 */
    @Transactional
    public Map<String, Object> reserve(Long projectId, BydMediaReservationRequest req, Long createdBy) {
        requireBydProject(projectId);
        if (createdBy == null) throw new IllegalArgumentException("未登录或缺少用户 id");
        if (req == null) throw new IllegalArgumentException("媒体参数不能为空");

        String mediaType = normalizeMediaType(req.getMediaType());
        String mimeType = normalizeMimeType(req.getMimeType());
        String fileName = normalizeFileName(req.getFileName());
        Long byteSize = req.getByteSize();
        Integer width = req.getWidth();
        Integer height = req.getHeight();
        BigDecimal duration = req.getDurationSeconds();

        if (byteSize == null || byteSize <= 0) throw new IllegalArgumentException("媒体文件大小必须大于 0");
        long maxBytes = TYPE_IMAGE.equals(mediaType) ? props.maxImageBytes() : props.maxVideoBytes();
        if (byteSize > maxBytes) {
            throw new IllegalArgumentException(TYPE_IMAGE.equals(mediaType)
                    ? "图片大小不能超过 " + props.getMediaMaxImageMb() + "MB"
                    : "视频大小不能超过 " + props.getMediaMaxVideoMb() + "MB");
        }
        if (width == null || width <= 0 || height == null || height <= 0) {
            throw new IllegalArgumentException("媒体宽高必须为正整数");
        }
        validateMimeAndExtension(mediaType, mimeType, fileName);
        if (TYPE_VIDEO.equals(mediaType)) {
            if (duration == null || duration.compareTo(BigDecimal.valueOf(2)) < 0
                    || duration.compareTo(BigDecimal.valueOf(120)) > 0) {
                throw new IllegalArgumentException("视频时长必须在 2–120 秒之间");
            }
        } else {
            duration = null;
        }

        LocalDateTime now = LocalDateTime.now();
        BydDynastyMediaEntity row = new BydDynastyMediaEntity();
        row.setMediaId(uuid32());
        row.setProjectId(projectId);
        row.setMediaType(mediaType);
        row.setSourceKind(SOURCE_STAGED);
        row.setStorageState(STATE_PENDING);
        row.setFileName(fileName);
        row.setMimeType(mimeType);
        row.setByteSize(byteSize);
        row.setWidth(width);
        row.setHeight(height);
        row.setDurationSeconds(duration);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusHours(Math.max(1, props.getMediaTtlHours())));
        if (mediaMapper.insert(row) != 1) throw new IllegalStateException("创建媒体预留失败，请稍后重试");

        Map<String, Object> out = mediaMap(row);
        out.put("uploadPath", uploadPath(projectId, row.getMediaId()));
        return out;
    }

    /**
     * 流式接收原始媒体字节，校验后先写 .part，再原子改名并更新 READY。
     */
    public Map<String, Object> completeUpload(Long projectId, String mediaId, long contentLength, InputStream input) {
        requireBydProject(projectId);
        BydDynastyMediaEntity row = requireProjectMedia(projectId, mediaId);
        if (!STATE_PENDING.equals(row.getStorageState())) {
            throw new IllegalStateException("媒体不是待上传状态，不能重复上传");
        }
        if (isExpired(row)) throw new IllegalStateException("媒体占位已过期，请重新选择文件");
        if (!SOURCE_STAGED.equals(row.getSourceKind())) {
            throw new IllegalStateException("图库媒体无需再次上传");
        }
        if (input == null) throw new IllegalArgumentException("上传内容不能为空");
        long declared = row.getByteSize() == null ? 0 : row.getByteSize();
        if (declared <= 0) throw new IllegalStateException("媒体预留缺少文件大小");
        if (contentLength >= 0 && contentLength != declared) {
            throw new IllegalArgumentException("上传内容大小与预留信息不一致");
        }
        long maxBytes = TYPE_IMAGE.equals(row.getMediaType()) ? props.maxImageBytes() : props.maxVideoBytes();
        if (declared > maxBytes) throw new IllegalArgumentException("媒体文件超过允许大小");
        if (contentLength > maxBytes) throw new IllegalArgumentException("媒体文件超过允许大小");

        Path root = props.mediaStorageRoot();
        Path projectDir = root.resolve(String.valueOf(projectId)).normalize();
        ensureInsideRoot(root, projectDir);
        // 每次上传使用独立文件名：并发 PUT 时即使都通过了前置状态检查，也只会有一个请求
        // 赢得数据库状态更新；落败请求只删除自己的文件，不能覆盖或误删胜出请求的文件。
        String uploadId = uuid32();
        Path part = projectDir.resolve(row.getMediaId() + "." + uploadId + ".part").normalize();
        ensureInsideRoot(root, part);
        Path finalPath = null;
        boolean movedByThisRequest = false;
        boolean promoted = false;
        try {
            Files.createDirectories(projectDir);
            long total = 0;
            byte[] head = new byte[16];
            int headLength = 0;
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[(int) COPY_BUFFER];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    if (headLength < head.length) {
                        int copy = Math.min(read, head.length - headLength);
                        System.arraycopy(buffer, 0, head, headLength, copy);
                        headLength += copy;
                    }
                    total += read;
                    if (total > maxBytes) throw new IllegalArgumentException("媒体文件超过允许大小");
                    if (total > declared) throw new IllegalArgumentException("上传内容大小与预留信息不一致");
                    out.write(buffer, 0, read);
                }
            }
            if (total == 0) throw new IllegalArgumentException("上传内容不能为空");
            if (total != declared) throw new IllegalArgumentException("上传内容大小与预留信息不一致");

            String ext = detectExtension(row.getMediaType(), head, headLength);
            if (!mimeMatches(row.getMediaType(), row.getMimeType(), ext)) {
                throw new IllegalArgumentException(TYPE_VIDEO.equals(row.getMediaType())
                        ? "视频仅支持 MP4 格式" : "图片格式与预留 MIME 不一致");
            }
            if (TYPE_IMAGE.equals(row.getMediaType())) validateImageReadable(part, ext);

            finalPath = projectDir.resolve(row.getMediaId() + "." + uploadId + "." + ext).normalize();
            ensureInsideRoot(root, finalPath);
            try {
                Files.move(part, finalPath, StandardCopyOption.ATOMIC_MOVE);
                movedByThisRequest = true;
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IllegalStateException("暂存目录不支持原子写入，请检查数据卷配置", ex);
            }

            String relativePath = root.relativize(finalPath).toString().replace('\\', '/');
            LocalDateTime now = LocalDateTime.now();
            int updated = mediaMapper.update(null, new UpdateWrapper<BydDynastyMediaEntity>()
                    .eq("id", row.getId())
                    .eq("storage_state", STATE_PENDING)
                    .set("storage_state", STATE_READY)
                    .set("storage_path", relativePath)
                    .set("byte_size", total)
                    .set("updated_at", now));
            if (updated != 1) throw new IllegalStateException("媒体状态更新失败，请重新上传");
            promoted = true;
            row.setStorageState(STATE_READY);
            row.setStoragePath(relativePath);
            row.setByteSize(total);
            row.setUpdatedAt(now);
            return mediaMap(row);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("保存媒体文件失败，请稍后重试", ex);
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // .part 清理失败不影响主错误；过期清理任务会兜底。
            }
            if (movedByThisRequest && !promoted && finalPath != null) {
                try {
                    Files.deleteIfExists(finalPath);
                } catch (IOException ex) {
                    log.warn("清理未完成状态更新的 BYD 媒体文件失败 mediaId={}: {}", mediaId, ex.getMessage());
                }
            }
        }
    }

    /** 将当前项目已有图库图片注册为 URL 媒体，最终仍由 Agent 上传 BYD CDN。 */
    @Transactional
    public Map<String, Object> registerFromImage(Long projectId, BydMediaFromImageRequest req, Long createdBy) {
        requireBydProject(projectId);
        if (createdBy == null) throw new IllegalArgumentException("未登录或缺少用户 id");
        if (req == null || req.getImageId() == null) throw new IllegalArgumentException("图库图片 id 不能为空");
        ImageAssetEntity image = imageMapper.selectById(req.getImageId());
        if (image == null || image.getProjectId() == null || !projectId.equals(image.getProjectId())) {
            throw new IllegalArgumentException("图库图片不存在或不属于当前项目");
        }
        if (image.getStorageKey() == null || image.getStorageKey().isBlank()) {
            throw new IllegalArgumentException("图库图片缺少可访问地址");
        }
        if (image.getWidth() == null || image.getWidth() <= 0
                || image.getHeight() == null || image.getHeight() <= 0) {
            throw new IllegalArgumentException("图库图片缺少宽高信息，请重新上传后再插入");
        }
        String url = imageStorage.publicUrl(image.getStorageKey());
        if (!isHttpUrl(url)) throw new IllegalStateException("图库图片地址不可用，请重新上传图片");

        String fileName = normalizeFileName(image.getFileName());
        String mimeType = guessImageMime(fileName, image.getStorageKey());
        if (mimeType == null) throw new IllegalArgumentException("图库图片格式不受支持");
        Integer width = image.getWidth();
        Integer height = image.getHeight();

        LocalDateTime now = LocalDateTime.now();
        BydDynastyMediaEntity row = new BydDynastyMediaEntity();
        row.setMediaId(uuid32());
        row.setProjectId(projectId);
        row.setMediaType(TYPE_IMAGE);
        row.setSourceKind(SOURCE_URL);
        row.setStorageState(STATE_READY);
        row.setSourceUrl(url);
        row.setFileName(fileName);
        row.setMimeType(mimeType);
        row.setByteSize(0L);
        row.setWidth(width);
        row.setHeight(height);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now.plusHours(Math.max(1, props.getMediaTtlHours())));
        if (mediaMapper.insert(row) != 1) throw new IllegalStateException("登记图库媒体失败，请稍后重试");
        return mediaMap(row);
    }

    /** 用户读取媒体准备状态；只按项目归属访问，不暴露服务器文件路径。 */
    public Map<String, Object> get(Long projectId, String mediaId) {
        requireBydProject(projectId);
        return mediaMap(requireProjectMedia(projectId, mediaId));
    }

    /** 用户删除占位后尽力清理；正在被活动任务引用的媒体拒绝删除。 */
    @Transactional
    public void delete(Long projectId, String mediaId) {
        requireBydProject(projectId);
        BydDynastyMediaEntity row = requireProjectMediaForUpdate(projectId, mediaId);
        if (activeJobReferences(projectId, mediaId)) {
            throw new IllegalStateException("媒体正在被推送任务使用，暂不能删除");
        }
        if (mediaMapper.deleteById(row.getId()) != 1) {
            throw new IllegalStateException("媒体删除失败，请稍后重试");
        }
        deleteStagedFileAfterCommit(row);
    }

    /**
     * 创建任务时解析并复核正文 token 对应的当前项目媒体。
     */
    @Transactional
    public BydDynastyMediaEntity requireReadyForPush(Long projectId, String mediaId, String expectedType) {
        requireBydProject(projectId);
        String id = safe(mediaId);
        if (id.isBlank() || id.length() > 64) throw new IllegalArgumentException("正文媒体标识无效");
        BydDynastyMediaEntity row = mediaMapper.selectOne(new QueryWrapper<BydDynastyMediaEntity>()
                .eq("media_id", id).last("LIMIT 1 FOR UPDATE"));
        if (row == null || isExpired(row)) {
            throw new IllegalArgumentException("正文引用的媒体不存在或已过期");
        }
        if (!projectId.equals(row.getProjectId())) {
            throw new IllegalArgumentException("正文媒体不属于当前项目");
        }
        if (!expectedType.equals(row.getMediaType())) {
            throw new IllegalArgumentException("正文媒体类型不匹配");
        }
        if (!STATE_READY.equals(row.getStorageState())) {
            throw new IllegalArgumentException("正文媒体尚未准备完成，请重新上传");
        }
        validateStoredMediaMetadata(row);
        if (SOURCE_STAGED.equals(row.getSourceKind())) {
            Path file = resolveStoredPath(row);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("暂存媒体文件不存在，请重新上传");
            }
        } else if (!isHttpUrl(row.getSourceUrl())) {
            throw new IllegalArgumentException("图库媒体地址不可用，请重新插入");
        }
        return row;
    }

    /** 任务创建事务内把引用媒体延长到至少 now + jobRetentionHours。 */
    public void extendLifetimeForJob(List<BydDynastyMediaEntity> rows, LocalDateTime now) {
        if (rows == null || rows.isEmpty()) return;
        LocalDateTime target = now.plusHours(Math.max(1, props.getMediaJobRetentionHours()));
        for (BydDynastyMediaEntity row : rows) {
            if (row.getId() == null) continue;
            mediaMapper.update(null, new UpdateWrapper<BydDynastyMediaEntity>()
                    .eq("id", row.getId())
                    .lt("expires_at", target)
                    .set("expires_at", target)
                    .set("updated_at", now));
            row.setExpiresAt(target);
        }
    }

    /**
     * Agent 任务范围下载鉴权：Token 已由 Controller 校验，这里再校验任务/Agent/引用/项目/过期。
     */
    public MediaDownload loadAgentDownload(String jobId, String mediaId, String requestedAgentId) {
        BydDynastyPushJobEntity job = jobMapper.selectOne(new QueryWrapper<BydDynastyPushJobEntity>()
                .eq("job_id", safe(jobId)).last("LIMIT 1"));
        if (job == null) throw new IllegalArgumentException("推送任务不存在");
        if (!"RUNNING".equals(job.getStatus()) && !"CLAIMED".equals(job.getStatus())) {
            throw new IllegalStateException("任务当前状态不允许下载媒体");
        }
        if (job.getAgentId() == null) throw new IllegalStateException("任务尚未绑定 Agent");
        authorizeAgent(job, requestedAgentId);

        requireBydProject(job.getProjectId());
        JsonNode payload = parsePayload(job.getPayloadJson());
        String payloadType = payloadStagedMediaType(payload, jobId, mediaId);
        if (payloadType == null) {
            throw new IllegalStateException("媒体不属于该任务 payload");
        }

        BydDynastyMediaEntity row = mediaMapper.selectOne(new QueryWrapper<BydDynastyMediaEntity>()
                .eq("media_id", safe(mediaId)).last("LIMIT 1"));
        if (row == null || isExpired(row)) throw new IllegalArgumentException("任务媒体不存在或已过期");
        if (!job.getProjectId().equals(row.getProjectId())) throw new IllegalStateException("任务媒体不属于项目");
        String expectedType = TYPE_IMAGE.equals(row.getMediaType()) ? "image"
                : TYPE_VIDEO.equals(row.getMediaType()) ? "video" : null;
        if (expectedType == null) throw new IllegalStateException("任务媒体类型无效");
        if (!expectedType.equals(payloadType)) throw new IllegalStateException("任务媒体类型与正文不一致");
        if (!STATE_READY.equals(row.getStorageState()) || !SOURCE_STAGED.equals(row.getSourceKind())) {
            throw new IllegalStateException("任务媒体尚未准备完成");
        }
        Path file = resolveStoredPath(row);
        if (!Files.isRegularFile(file)) throw new IllegalStateException("任务媒体文件不存在");
        try {
            return new MediaDownload(new FileSystemResource(file), row.getFileName(), row.getMimeType(), Files.size(file));
        } catch (IOException ex) {
            throw new IllegalStateException("读取任务媒体失败", ex);
        }
    }

    /** 定时清理过期且没有被 PENDING/CLAIMED/RUNNING 任务引用的记录与文件。 */
    @Scheduled(cron = "${sparkora.byd.dynasty.media-cleanup-cron:0 17 * * * ?}")
    @Transactional
    public int cleanupExpired() {
        List<BydDynastyMediaEntity> expired = mediaMapper.selectList(new QueryWrapper<BydDynastyMediaEntity>()
                .lt("expires_at", LocalDateTime.now())
                .orderByAsc("expires_at", "id")
                .last("LIMIT 500 FOR UPDATE SKIP LOCKED"));
        int removed = 0;
        for (BydDynastyMediaEntity row : expired) {
            if (row.getMediaId() == null || activeJobReferences(row.getProjectId(), row.getMediaId())) continue;
            if (mediaMapper.deleteById(row.getId()) > 0) {
                deleteStagedFileAfterCommit(row);
                removed++;
            }
        }
        int orphanedParts = cleanupOrphanedParts();
        if (removed > 0 || orphanedParts > 0) {
            log.info("清理过期 BYD 暂存媒体 {} 条、孤立上传临时文件 {} 个", removed, orphanedParts);
        }
        return removed;
    }

    /** 清理上传中断遗留的 .part；只删除一小时前的文件，避免碰到仍在进行的请求。 */
    private int cleanupOrphanedParts() {
        Path root = props.mediaStorageRoot();
        if (!Files.isDirectory(root)) return 0;
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".part")) continue;
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) continue;
                    if (Files.deleteIfExists(path)) removed++;
                } catch (IOException ex) {
                    log.warn("清理 BYD 孤立上传临时文件失败 path={}: {}", path, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("扫描 BYD 孤立上传临时文件失败: {}", ex.getMessage());
        }
        return removed;
    }

    private void authorizeAgent(BydDynastyPushJobEntity job, String requestedAgentId) {
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("缺少 Agent ID");
        }
        BydDynastyAgentEntity bound = agentMapper.selectById(job.getAgentId());
        if (bound == null || bound.getOwnerUserId() == null
                || !bound.getOwnerUserId().equals(props.getAgentOwnerUserId())) {
            throw new IllegalStateException("任务不属于当前 Agent");
        }
        if (!requestedAgentId.trim().equals(bound.getAgentId())) {
            throw new IllegalStateException("任务不属于当前 Agent");
        }
    }

    private String payloadStagedMediaType(JsonNode payload, String jobId, String mediaId) {
        JsonNode media = payload.path("media");
        if (!media.isArray()) return null;
        String expected = "/api/agent/byd-dynasty/jobs/" + safe(jobId) + "/media/" + safe(mediaId);
        for (JsonNode item : media) {
            if (!safe(mediaId).equals(text(item, "mediaId"))) continue;
            if (!SOURCE_STAGED.equals(text(item, "sourceType"))) continue;
            if (!expected.equals(text(item, "downloadPath"))) continue;
            return text(item, "type").toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private JsonNode parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalStateException("任务 payload 为空");
        try {
            return json.readTree(payloadJson);
        } catch (Exception ex) {
            throw new IllegalStateException("任务 payload 无法解析", ex);
        }
    }

    private boolean activeJobReferences(Long projectId, String mediaId) {
        if (projectId == null || mediaId == null || mediaId.isBlank()) return false;
        List<BydDynastyPushJobEntity> jobs = jobMapper.selectList(new QueryWrapper<BydDynastyPushJobEntity>()
                .eq("project_id", projectId)
                .in("status", "PENDING", "CLAIMED", "RUNNING"));
        for (BydDynastyPushJobEntity job : jobs) {
            try {
                JsonNode payload = parsePayload(job.getPayloadJson());
                for (JsonNode item : payload.path("media")) {
                    if (mediaId.equals(text(item, "mediaId"))) return true;
                }
            } catch (RuntimeException ex) {
                // 解析失败时保守保护，避免清理任务误删仍可能被下载的媒体。
                log.warn("检查活动任务媒体引用失败，暂不删除 mediaId={}: {}", mediaId, ex.getMessage());
                return true;
            }
        }
        return false;
    }

    private void deleteStagedFileAfterCommit(BydDynastyMediaEntity row) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStagedFileQuietly(row);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStagedFileQuietly(row);
            }
        });
    }

    private void deleteStagedFileQuietly(BydDynastyMediaEntity row) {
        if (!SOURCE_STAGED.equals(row.getSourceKind())) return;
        try {
            Files.deleteIfExists(resolveStoredPath(row));
        } catch (Exception ex) {
            log.warn("删除 BYD 暂存媒体文件失败 mediaId={}: {}", row.getMediaId(), ex.getMessage());
        }
    }

    private Path resolveStoredPath(BydDynastyMediaEntity row) {
        if (row.getStoragePath() == null || row.getStoragePath().isBlank()) {
            throw new IllegalStateException("暂存媒体缺少文件路径");
        }
        Path root = props.mediaStorageRoot();
        Path candidate = root.resolve(row.getStoragePath()).normalize();
        ensureInsideRoot(root, candidate);
        return candidate;
    }

    private void validateStoredMediaMetadata(BydDynastyMediaEntity row) {
        long max = TYPE_IMAGE.equals(row.getMediaType()) ? props.maxImageBytes() : props.maxVideoBytes();
        if (row.getByteSize() == null || row.getByteSize() < 0 || row.getByteSize() > max) {
            throw new IllegalArgumentException("正文媒体大小不合法，请重新上传");
        }
        if (SOURCE_STAGED.equals(row.getSourceKind()) && row.getByteSize() <= 0) {
            throw new IllegalArgumentException("正文暂存媒体大小不合法，请重新上传");
        }
        if (row.getWidth() == null || row.getWidth() <= 0 || row.getHeight() == null || row.getHeight() <= 0) {
            throw new IllegalArgumentException("正文媒体宽高信息不合法，请重新上传");
        }
        if (TYPE_VIDEO.equals(row.getMediaType())) {
            if (!"video/mp4".equals(row.getMimeType()) || row.getDurationSeconds() == null
                    || row.getDurationSeconds().compareTo(BigDecimal.valueOf(2)) < 0
                    || row.getDurationSeconds().compareTo(BigDecimal.valueOf(120)) > 0) {
                throw new IllegalArgumentException("视频格式或时长不符合小程序要求");
            }
        }
    }

    private ArticleProjectEntity requireBydProject(Long projectId) {
        if (projectId == null) throw new IllegalArgumentException("项目 id 不能为空");
        ArticleProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        if (!CHANNEL_BYD_MINIAPP.equals(project.getChannel())) {
            throw new IllegalArgumentException("仅比亚迪王朝小程序项目可执行该操作");
        }
        return project;
    }

    private BydDynastyMediaEntity requireProjectMediaForUpdate(Long projectId, String mediaId) {
        return requireProjectMedia(projectId, mediaId, true);
    }

    private BydDynastyMediaEntity requireProjectMedia(Long projectId, String mediaId) {
        return requireProjectMedia(projectId, mediaId, false);
    }

    private BydDynastyMediaEntity requireProjectMedia(Long projectId, String mediaId, boolean forUpdate) {
        String id = safe(mediaId);
        if (id.isBlank()) throw new IllegalArgumentException("媒体 id 不能为空");
        BydDynastyMediaEntity row = mediaMapper.selectOne(new QueryWrapper<BydDynastyMediaEntity>()
                .eq("project_id", projectId).eq("media_id", id)
                .last(forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (row == null) throw new IllegalArgumentException("媒体不存在或不属于当前项目");
        return row;
    }

    private Map<String, Object> mediaMap(BydDynastyMediaEntity row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mediaId", row.getMediaId());
        out.put("mediaType", row.getMediaType());
        out.put("sourceType", row.getSourceKind());
        out.put("storageState", row.getStorageState());
        out.put("ready", STATE_READY.equals(row.getStorageState()));
        out.put("fileName", row.getFileName());
        out.put("mimeType", row.getMimeType());
        out.put("byteSize", row.getByteSize());
        out.put("width", row.getWidth());
        out.put("height", row.getHeight());
        if (row.getDurationSeconds() != null) out.put("durationSeconds", row.getDurationSeconds());
        if (SOURCE_URL.equals(row.getSourceKind())) out.put("url", row.getSourceUrl());
        out.put("expiresAt", row.getExpiresAt());
        return out;
    }

    private String uploadPath(Long projectId, String mediaId) {
        return "/api/projects/" + projectId + "/byd-dynasty/media/" + mediaId + "/content";
    }

    private void validateMimeAndExtension(String type, String mime, String fileName) {
        String ext = extension(fileName);
        if (TYPE_IMAGE.equals(type)) {
            boolean mimeOk = "image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime);
            boolean extOk = "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext);
            if (!mimeOk || !extOk || !mimeMatches(type, mime, ext)) {
                throw new IllegalArgumentException("图片仅支持 JPEG、PNG 或 WebP 格式");
            }
        } else if (!"video/mp4".equals(mime) || !"mp4".equals(ext)) {
            throw new IllegalArgumentException("视频仅支持 MP4 格式");
        }
    }

    private void validateImageReadable(Path file, String ext) {
        if ("webp".equals(ext)) return;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalArgumentException("图片内容无法解析，请重新选择");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("图片内容无法解析，请重新选择");
        }
    }

    private String detectExtension(String type, byte[] head, int length) {
        if (TYPE_IMAGE.equals(type)) {
            if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "png";
            if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "jpg";
            if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "webp";
            throw new IllegalArgumentException("图片内容不是受支持的 JPEG、PNG 或 WebP");
        }
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return "mp4";
        throw new IllegalArgumentException("视频内容不是有效的 MP4 文件");
    }

    private boolean mimeMatches(String type, String mime, String ext) {
        if (TYPE_VIDEO.equals(type)) return "video/mp4".equals(mime) && "mp4".equals(ext);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg".equals(mime);
        if ("png".equals(ext)) return "image/png".equals(mime);
        if ("webp".equals(ext)) return "image/webp".equals(mime);
        return false;
    }

    private String normalizeMediaType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_IMAGE.equals(type) && !TYPE_VIDEO.equals(type)) {
            throw new IllegalArgumentException("媒体类型仅支持 IMAGE 或 VIDEO");
        }
        return type;
    }

    private String normalizeMimeType(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("媒体 MIME 类型不能为空");
        String mime = value.trim().toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        return semicolon >= 0 ? mime.substring(0, semicolon).trim() : mime;
    }

    private String normalizeFileName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("媒体文件名不能为空");
        String name = value.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\r\\n\\t\\x00-\\x1f\\x7f]", "_")
                .replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) throw new IllegalArgumentException("媒体文件名不能为空");
        if (name.length() > 255) {
            String ext = extension(name);
            int keep = Math.max(1, 250 - ext.length());
            name = name.substring(0, Math.min(name.length(), keep)) + (ext.isBlank() ? "" : "." + ext);
        }
        return name;
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String guessImageMime(String fileName, String storageKey) {
        String ext = extension(fileName);
        if (ext.isBlank()) ext = extension(storageKey);
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> null;
        };
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

    private void ensureInsideRoot(Path root, Path candidate) {
        if (candidate == null || !candidate.startsWith(root)) {
            throw new IllegalStateException("媒体存储路径不合法");
        }
    }

    private boolean isExpired(BydDynastyMediaEntity row) {
        return row.getExpiresAt() == null || !row.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 成功下载响应所需的受控资源描述。 */
    public record MediaDownload(Resource resource, String fileName, String mimeType, long byteSize) {
    }
}
