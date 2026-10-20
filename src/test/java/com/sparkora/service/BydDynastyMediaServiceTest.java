package com.sparkora.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** BYD 媒体暂存、格式/大小校验、生命周期与 Agent 任务范围下载测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BydDynastyMediaServiceTest {

    @Mock BydDynastyMediaMapper mediaMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock BydDynastyPushJobMapper jobMapper;
    @Mock BydDynastyAgentMapper agentMapper;
    @Mock ImageStorage imageStorage;

    @TempDir Path tempDir;

    private BydDynastyProperties props;
    private BydDynastyMediaService service;

    @BeforeEach
    void setUp() {
        props = new BydDynastyProperties();
        props.setAgentOwnerUserId(7L);
        props.setMediaStorageDir(tempDir.toString());
        props.setMediaTtlHours(24);
        props.setMediaJobRetentionHours(72);
        service = new BydDynastyMediaService(mediaMapper, projectMapper, imageMapper,
                jobMapper, agentMapper, imageStorage, props, new ObjectMapper());
        when(projectMapper.selectById(1L)).thenReturn(project());
    }

    @Test
    void 预留图片记录归属和上传地址但不创建文件() {
        when(mediaMapper.insert(any(BydDynastyMediaEntity.class))).thenAnswer(invocation -> {
            invocation.<BydDynastyMediaEntity>getArgument(0).setId(10L);
            return 1;
        });
        BydMediaReservationRequest req = request("image", "photo.png", "image/png", 128, 4, 3, null);

        Map<String, Object> result = service.reserve(1L, req, 7L);

        assertEquals("PENDING", result.get("storageState"));
        assertEquals("IMAGE", result.get("mediaType"));
        assertTrue(((String) result.get("uploadPath")).contains("/byd-dynasty/media/"));
        assertFalse(Files.exists(tempDir.resolve("1")));

        var captor = org.mockito.ArgumentCaptor.forClass(BydDynastyMediaEntity.class);
        verify(mediaMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getProjectId());
        assertEquals(7L, captor.getValue().getCreatedBy());
        assertEquals("STAGED", captor.getValue().getSourceKind());
        assertNotNull(captor.getValue().getExpiresAt());
    }

    @Test
    void 预留时拒绝超限大小和非法视频时长() {
        BydMediaReservationRequest oversized = request("image", "photo.jpg", "image/jpeg",
                props.maxImageBytes() + 1, 4, 3, null);
        IllegalArgumentException sizeError = assertThrows(IllegalArgumentException.class,
                () -> service.reserve(1L, oversized, 7L));
        assertTrue(sizeError.getMessage().contains("不能超过"));

        BydMediaReservationRequest tooShort = request("video", "clip.mp4", "video/mp4",
                1024, 1920, 1080, new BigDecimal("1.9"));
        IllegalArgumentException durationError = assertThrows(IllegalArgumentException.class,
                () -> service.reserve(1L, tooShort, 7L));
        assertTrue(durationError.getMessage().contains("2–120"));
        verify(mediaMapper, never()).insert(any(BydDynastyMediaEntity.class));
    }

    @Test
    void 完整上传先写临时文件再原子落盘并更新READY() throws Exception {
        byte[] png = pngBytes();
        BydDynastyMediaEntity row = pending("media1", BydDynastyMediaService.TYPE_IMAGE,
                "photo.png", "image/png", png.length);
        when(mediaMapper.selectOne(any())).thenReturn(row);
        when(mediaMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> result = service.completeUpload(1L, "media1",
                png.length, new ByteArrayInputStream(png));

        assertTrue(row.getStoragePath().startsWith("1/media1."));
        assertTrue(row.getStoragePath().endsWith(".png"));
        Path finalFile = tempDir.resolve(row.getStoragePath());
        assertTrue(Files.isRegularFile(finalFile));
        assertEquals(png.length, Files.size(finalFile));
        assertEquals("READY", result.get("storageState"));
        try (var files = Files.list(tempDir.resolve("1"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
        verify(mediaMapper).update(isNull(), any());
    }

    @Test
    void 并发上传落败请求不会删除胜出请求文件() throws Exception {
        byte[] png = pngBytes();
        BydDynastyMediaEntity row = pending("media1", BydDynastyMediaService.TYPE_IMAGE,
                "photo.png", "image/png", png.length);
        when(mediaMapper.selectOne(any())).thenReturn(row);
        when(mediaMapper.update(isNull(), any())).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.completeUpload(1L, "media1", png.length, new ByteArrayInputStream(png)));

        assertTrue(ex.getMessage().contains("状态更新失败"));
        try (var files = Files.list(tempDir.resolve("1"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void 上传声明大小不一致时不落最终文件() throws Exception {
        byte[] png = pngBytes();
        BydDynastyMediaEntity row = pending("media1", BydDynastyMediaService.TYPE_IMAGE,
                "photo.png", "image/png", png.length);
        when(mediaMapper.selectOne(any())).thenReturn(row);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.completeUpload(1L, "media1", png.length + 1, new ByteArrayInputStream(png)));

        assertTrue(ex.getMessage().contains("大小与预留信息不一致"));
        assertFalse(Files.exists(tempDir.resolve("1/media1.png")));
        verify(mediaMapper, never()).update(isNull(), any());
    }

    @Test
    void 文件魔数与预留MIME不一致时拒绝() throws Exception {
        byte[] jpeg = jpegBytes();
        BydDynastyMediaEntity row = pending("media1", BydDynastyMediaService.TYPE_IMAGE,
                "photo.png", "image/png", jpeg.length);
        when(mediaMapper.selectOne(any())).thenReturn(row);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.completeUpload(1L, "media1", jpeg.length, new ByteArrayInputStream(jpeg)));

        assertTrue(ex.getMessage().contains("格式与预留 MIME 不一致"));
        assertFalse(Files.exists(tempDir.resolve("1/media1.png")));
        verify(mediaMapper, never()).update(isNull(), any());
    }

    @Test
    void 视频仅接受MP4魔数() {
        byte[] fake = "not-an-mp4-video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        BydDynastyMediaEntity row = pending("video1", BydDynastyMediaService.TYPE_VIDEO,
                "clip.mp4", "video/mp4", fake.length);
        row.setDurationSeconds(new BigDecimal("12.3"));
        when(mediaMapper.selectOne(any())).thenReturn(row);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.completeUpload(1L, "video1", fake.length, new ByteArrayInputStream(fake)));

        assertTrue(ex.getMessage().contains("有效的 MP4"));
        verify(mediaMapper, never()).update(isNull(), any());
    }

    @Test
    void 图库图片登记为URL媒体且必须带真实宽高() {
        ImageAssetEntity image = new ImageAssetEntity();
        image.setId(55L);
        image.setProjectId(1L);
        image.setFileName("gallery.jpg");
        image.setStorageKey("images/gallery.jpg");
        when(imageMapper.selectById(55L)).thenReturn(image);

        BydMediaFromImageRequest missingSize = new BydMediaFromImageRequest();
        missingSize.setImageId(55L);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerFromImage(1L, missingSize, 7L));
        assertTrue(ex.getMessage().contains("宽高"));

        image.setWidth(1200);
        image.setHeight(800);
        when(imageStorage.publicUrl("images/gallery.jpg")).thenReturn("https://images.example/gallery.jpg");
        when(mediaMapper.insert(any(BydDynastyMediaEntity.class))).thenAnswer(invocation -> {
            invocation.<BydDynastyMediaEntity>getArgument(0).setId(11L);
            return 1;
        });

        Map<String, Object> result = service.registerFromImage(1L, missingSize, 7L);

        assertEquals("URL", result.get("sourceType"));
        assertEquals("READY", result.get("storageState"));
        assertEquals("https://images.example/gallery.jpg", result.get("url"));
    }

    @Test
    void 正文媒体解析拒绝跨项目过期和类型不匹配() {
        BydDynastyMediaEntity crossProject = readyStaged("image1", BydDynastyMediaService.TYPE_IMAGE);
        crossProject.setProjectId(2L);
        when(mediaMapper.selectOne(any())).thenReturn(crossProject);
        IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                () -> service.requireReadyForPush(1L, "image1", BydDynastyMediaService.TYPE_IMAGE));
        assertTrue(cross.getMessage().contains("不属于当前项目"));

        BydDynastyMediaEntity expired = readyStaged("image2", BydDynastyMediaService.TYPE_IMAGE);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mediaMapper.selectOne(any())).thenReturn(expired);
        IllegalArgumentException expiry = assertThrows(IllegalArgumentException.class,
                () -> service.requireReadyForPush(1L, "image2", BydDynastyMediaService.TYPE_IMAGE));
        assertTrue(expiry.getMessage().contains("过期"));

        BydDynastyMediaEntity video = readyStaged("video1", BydDynastyMediaService.TYPE_VIDEO);
        video.setDurationSeconds(new BigDecimal("10"));
        video.setMimeType("video/mp4");
        when(mediaMapper.selectOne(any())).thenReturn(video);
        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> service.requireReadyForPush(1L, "video1", BydDynastyMediaService.TYPE_IMAGE));
        assertTrue(type.getMessage().contains("类型不匹配"));
    }

    @Test
    void 任务范围下载要求领取Agent_payload引用和READY暂存文件() throws Exception {
        Path file = tempDir.resolve("1/media1.jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, jpegBytes());
        BydDynastyPushJobEntity job = downloadJob();
        BydDynastyAgentEntity agent = agent();
        BydDynastyMediaEntity row = readyStaged("media1", BydDynastyMediaService.TYPE_IMAGE);
        row.setStoragePath("1/media1.jpg");
        row.setMimeType("image/jpeg");
        row.setFileName("photo.jpg");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        when(mediaMapper.selectOne(any())).thenReturn(row);

        BydDynastyMediaService.MediaDownload download =
                service.loadAgentDownload("job1", "media1", "local-win");

        assertEquals("photo.jpg", download.fileName());
        assertEquals("image/jpeg", download.mimeType());
        assertEquals(Files.size(file), download.byteSize());
        assertTrue(download.resource().isReadable());
    }

    @Test
    void 任务范围下载缺少AgentId_错误Agent或未引用媒体均拒绝() {
        BydDynastyPushJobEntity job = downloadJob();
        when(jobMapper.selectOne(any())).thenReturn(job);
        assertThrows(IllegalArgumentException.class,
                () -> service.loadAgentDownload("job1", "media1", " "));

        when(agentMapper.selectById(3L)).thenReturn(agent());
        assertThrows(IllegalStateException.class,
                () -> service.loadAgentDownload("job1", "media1", "another-agent"));

        job.setPayloadJson("{\"media\":[]}");
        assertThrows(IllegalStateException.class,
                () -> service.loadAgentDownload("job1", "media1", "local-win"));
    }

    @Test
    void 过期清理删除文件和记录但不碰活动任务引用() throws Exception {
        Path file = tempDir.resolve("1/expired.jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, jpegBytes());
        BydDynastyMediaEntity expired = readyStaged("expired", BydDynastyMediaService.TYPE_IMAGE);
        expired.setId(30L);
        expired.setStoragePath("1/expired.jpg");
        expired.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(mediaMapper.selectList(any())).thenReturn(List.of(expired));
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(mediaMapper.deleteById(30L)).thenReturn(1);

        int removed = service.cleanupExpired();

        assertEquals(1, removed);
        assertFalse(Files.exists(file));
        verify(mediaMapper).deleteById(30L);
    }

    @Test
    void 清理任务删除超过一小时的孤立part并保留新part() throws Exception {
        Path projectDir = tempDir.resolve("1");
        Files.createDirectories(projectDir);
        Path oldPart = projectDir.resolve("interrupted.part");
        Path recentPart = projectDir.resolve("active.part");
        Files.writeString(oldPart, "old");
        Files.writeString(recentPart, "recent");
        Files.setLastModifiedTime(oldPart, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2 * 60 * 60 * 1000L));
        when(mediaMapper.selectList(any())).thenReturn(List.of());

        int removed = service.cleanupExpired();

        assertEquals(0, removed);
        assertFalse(Files.exists(oldPart));
        assertTrue(Files.exists(recentPart));
    }

    @Test
    void 所有媒体入口拒绝gzh项目() {
        ArticleProjectEntity gzh = project();
        gzh.setChannel("gzh");
        when(projectMapper.selectById(1L)).thenReturn(gzh);

        assertThrows(IllegalArgumentException.class,
                () -> service.reserve(1L, request("image", "a.jpg", "image/jpeg", 10, 1, 1, null), 7L));
        assertThrows(IllegalArgumentException.class,
                () -> service.completeUpload(1L, "media1", 10, new ByteArrayInputStream(new byte[10])));
        BydMediaFromImageRequest fromImage = new BydMediaFromImageRequest();
        fromImage.setImageId(55L);
        assertThrows(IllegalArgumentException.class, () -> service.registerFromImage(1L, fromImage, 7L));
        assertThrows(IllegalArgumentException.class, () -> service.get(1L, "media1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.requireReadyForPush(1L, "media1", BydDynastyMediaService.TYPE_IMAGE));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1L, "media1"));
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(1L);
        p.setChannel("byd_miniapp");
        return p;
    }

    private BydMediaReservationRequest request(String type, String fileName, String mime,
                                                long size, int width, int height, BigDecimal duration) {
        BydMediaReservationRequest req = new BydMediaReservationRequest();
        req.setMediaType(type);
        req.setFileName(fileName);
        req.setMimeType(mime);
        req.setByteSize(size);
        req.setWidth(width);
        req.setHeight(height);
        req.setDurationSeconds(duration);
        return req;
    }

    private BydDynastyMediaEntity pending(String mediaId, String type, String fileName,
                                         String mime, long size) {
        BydDynastyMediaEntity row = new BydDynastyMediaEntity();
        row.setId(10L);
        row.setMediaId(mediaId);
        row.setProjectId(1L);
        row.setMediaType(type);
        row.setSourceKind(BydDynastyMediaService.SOURCE_STAGED);
        row.setStorageState(BydDynastyMediaService.STATE_PENDING);
        row.setFileName(fileName);
        row.setMimeType(mime);
        row.setByteSize(size);
        row.setWidth(800);
        row.setHeight(600);
        row.setCreatedBy(7L);
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        return row;
    }

    private BydDynastyMediaEntity readyStaged(String mediaId, String type) {
        BydDynastyMediaEntity row = pending(mediaId, type, "photo.jpg", "image/jpeg", 100);
        row.setSourceKind(BydDynastyMediaService.SOURCE_STAGED);
        row.setStorageState(BydDynastyMediaService.STATE_READY);
        return row;
    }

    private BydDynastyPushJobEntity downloadJob() {
        BydDynastyPushJobEntity job = new BydDynastyPushJobEntity();
        job.setId(20L);
        job.setJobId("job1");
        job.setProjectId(1L);
        job.setAgentId(3L);
        job.setStatus("RUNNING");
        job.setPayloadJson("{\"media\":[{\"mediaId\":\"media1\",\"sourceType\":\"STAGED\","
                + "\"type\":\"image\",\"downloadPath\":\"/api/agent/byd-dynasty/jobs/job1/media/media1\"}]}");
        return job;
    }

    private BydDynastyAgentEntity agent() {
        BydDynastyAgentEntity agent = new BydDynastyAgentEntity();
        agent.setId(3L);
        agent.setAgentId("local-win");
        agent.setOwnerUserId(7L);
        agent.setStatus("ONLINE");
        return agent;
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] jpegBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}