package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.sparkora.web.dto.BydAgentRegisterRequest;
import com.sparkora.web.dto.BydAgentResultRequest;
import com.sparkora.web.dto.BydPushJobCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BYD 推送 payload v2、渠道门禁、媒体映射与任务状态契约测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BydDynastyPushServiceTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock BydDynastyMediaService mediaService;
    @Mock BydDynastyAgentMapper agentMapper;
    @Mock BydDynastyPushJobMapper jobMapper;

    private BydDynastyProperties props;
    private BydDynastyPushService service;

    @BeforeEach
    void setUp() {
        props = new BydDynastyProperties();
        props.setAgentToken("agent-secret");
        props.setAgentOwnerUserId(7L);
        props.setDefaultClubId("220");
        props.setDefaultClubTitle("四川迪友会");
        service = new BydDynastyPushService(projectMapper, versionMapper, mediaService,
                agentMapper, jobMapper, props, new ObjectMapper());
    }

    @Test
    void agentToken使用专属常量比较且JWT无关() {
        assertTrue(service.agentAuthorized("agent-secret"));
        assertFalse(service.agentAuthorized("agent-secret "));
        assertFalse(service.agentAuthorized("wrong"));
        assertFalse(service.agentAuthorized(null));
        props.setAgentToken(" ");
        assertFalse(service.agentAuthorized("agent-secret"));
    }

    @Test
    void agent注册保存预检并绑定配置宿主用户() {
        BydAgentRegisterRequest req = new BydAgentRegisterRequest();
        req.setAgentId("local-win");
        req.setMachineName("OFFICE-WIN");
        req.setPreflight(Map.of("ready", true));
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(agentMapper.insert(any(BydDynastyAgentEntity.class))).thenReturn(1);

        Map<String, Object> result = service.registerAgent(req);

        assertEquals(Boolean.TRUE, result.get("preflightReady"));
        assertEquals(7L, result.get("ownerUserId"));
        assertEquals("local-win", result.get("agentId"));
        ArgumentCaptor<BydDynastyAgentEntity> captor = ArgumentCaptor.forClass(BydDynastyAgentEntity.class);
        verify(agentMapper).insert(captor.capture());
        assertEquals("ONLINE", captor.getValue().getStatus());
        assertTrue(captor.getValue().getLastPreflightJson().contains("\"ready\":true"));
    }

    @Test
    void gzh项目的state_createJob_getJob全部拒绝且不落任务() {
        ArticleProjectEntity p = project();
        p.setChannel("gzh");
        when(projectMapper.selectById(88L)).thenReturn(p);

        assertThrows(IllegalArgumentException.class, () -> service.state(88L, 7L));
        assertThrows(IllegalArgumentException.class, () -> service.createJob(88L, createRequest(), 7L));
        assertThrows(IllegalArgumentException.class, () -> service.getJob(88L, "job-id"));

        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(jobMapper, never()).selectOne(any());
    }

    @Test
    void agent离线时创建任务直接失败不落任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.createJob(88L, new BydPushJobCreateRequest(), 7L));
        assertTrue(ex.getMessage().contains("未注册或离线"));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void payloadV2按正文顺序映射图片视频且不注入封面() {
        ArticleProjectEntity p = project();
        p.setStatus("DRAFT");
        String markdown = "# 第一段\n\n**粗体** 和 [链接](https://example.com/x)<br>第二行\n\n"
                + "![插图](sparkora-byd-media:image_01)\n\n尾段\n\n![视频](sparkora-byd-video:video-01)";
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(versionMapper.selectById(99L)).thenReturn(version(markdown));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(mediaService.requireReadyForPush(88L, "image_01", BydDynastyMediaService.TYPE_IMAGE))
                .thenReturn(image("image_01", BydDynastyMediaService.SOURCE_STAGED));
        when(mediaService.requireReadyForPush(88L, "video-01", BydDynastyMediaService.TYPE_VIDEO))
                .thenReturn(video("video-01"));
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals(2, payload.get("payloadVersion"));
        assertEquals("文章标题", payload.get("title"));
        assertEquals("220", payload.get("clubId"));
        assertEquals("四川迪友会", payload.get("clubTitle"));
        assertNotNull(payload.get("draftId"));
        assertNotNull(payload.get("jobId"));
        assertFalse(payload.containsKey("cover"));
        assertFalse(payload.containsKey("images"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(4, messages.size(), messages.toString());
        assertEquals("text", messages.get(0).get("type"));
        String text = (String) messages.get(0).get("content");
        assertTrue(text.contains("第一段"));
        assertTrue(text.contains("粗体"));
        assertTrue(text.contains("链接"));
        assertTrue(text.contains("第二行"));
        assertFalse(text.contains("#"));
        assertFalse(text.contains("**"));
        assertFalse(text.contains("https://"));
        assertEquals(Map.of("type", "img", "mediaRef", "MEDIA-1"), messages.get(1));
        assertEquals("text", messages.get(2).get("type"));
        assertEquals(Map.of("type", "video", "mediaRef", "MEDIA-2"), messages.get(3));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> media = (List<Map<String, Object>>) payload.get("media");
        assertEquals(2, media.size(), media.toString());
        assertEquals("MEDIA-1", media.get(0).get("id"));
        assertEquals("image_01", media.get(0).get("mediaId"));
        assertEquals("image", media.get(0).get("type"));
        assertEquals("STAGED", media.get(0).get("sourceType"));
        assertTrue(((String) media.get(0).get("downloadPath")).endsWith("/media/image_01"));
        assertEquals("MEDIA-2", media.get(1).get("id"));
        assertEquals("video", media.get(1).get("type"));
        assertEquals(new BigDecimal("12.300"), media.get(1).get("durationSeconds"));

        String raw = data.toString();
        assertFalse(raw.contains("COVER-"));
        assertFalse(raw.toLowerCase().contains("mmkv"));
        assertFalse(raw.toLowerCase().contains("usrmmkvstorage"));
        verify(mediaService).extendLifetimeForJob(anyList(), any(LocalDateTime.class));
        verify(jobMapper).insert(any(BydDynastyPushJobEntity.class));
    }

    @Test
    void 图库URL媒体使用URL下载而不暴露任务下载路径() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](sparkora-byd-media:image_02)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(mediaService.requireReadyForPush(88L, "image_02", BydDynastyMediaService.TYPE_IMAGE))
                .thenReturn(image("image_02", BydDynastyMediaService.SOURCE_URL));
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> media = (List<Map<String, Object>>) payload.get("media");
        assertEquals("URL", media.get(0).get("sourceType"));
        assertEquals("https://images.example/gallery.jpg", media.get(0).get("url"));
        assertFalse(media.get(0).containsKey("downloadPath"));
    }

    @Test
    void 历史HTTP图片URL继续编译为URL媒体() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](https://legacy.example/only.jpg)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(List.of(Map.of("type", "img", "mediaRef", "MEDIA-1")), messages);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> media = (List<Map<String, Object>>) payload.get("media");
        assertEquals("URL", media.get(0).get("sourceType"));
        assertEquals("https://legacy.example/only.jpg", media.get(0).get("url"));
        verify(mediaService, never()).requireReadyForPush(any(), anyString(), anyString());
    }

    @Test
    void 四个视频在落任务前被拒绝且最多只解析三个() {
        String markdown = "![v1](sparkora-byd-video:v1)\n\n![v2](sparkora-byd-video:v2)\n\n"
                + "![v3](sparkora-byd-video:v3)\n\n![v4](sparkora-byd-video:v4)";
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version(markdown));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(mediaService.requireReadyForPush(eq(88L), anyString(), eq(BydDynastyMediaService.TYPE_VIDEO)))
                .thenReturn(video("v"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, createRequest(), 7L));

        assertTrue(ex.getMessage().contains("最多支持 3 个视频"), ex.getMessage());
        verify(mediaService, times(3)).requireReadyForPush(eq(88L), anyString(), eq(BydDynastyMediaService.TYPE_VIDEO));
        verify(mediaService, never()).extendLifetimeForJob(anyList(), any(LocalDateTime.class));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
    }

    @Test
    void 媒体不存在或已过期时不插入任务() {
        assertMediaFailure(new IllegalArgumentException("正文引用的媒体不存在或已过期"));
    }

    @Test
    void 跨项目媒体时不插入任务() {
        assertMediaFailure(new IllegalArgumentException("正文媒体不属于当前项目"));
    }

    @Test
    void 媒体类型错误时不插入任务() {
        assertMediaFailure(new IllegalArgumentException("正文媒体类型不匹配"));
    }

    @Test
    void 公众号token在创建任务前拒绝() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](sparkora-img:gzh-token)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, createRequest(), 7L));
        assertTrue(ex.getMessage().contains("公众号暂存图片"));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
    }

    @Test
    void 只有图片的正文仍可创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](sparkora-byd-media:image_03)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(mediaService.requireReadyForPush(88L, "image_03", BydDynastyMediaService.TYPE_IMAGE))
                .thenReturn(image("image_03", BydDynastyMediaService.SOURCE_STAGED));
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(1, messages.size(), messages.toString());
        assertEquals(Map.of("type", "img", "mediaRef", "MEDIA-1"), messages.get(0));
    }

    @Test
    void 标题经过行内清洗后写入payload() {
        ArticleVersionEntity v = version("正文");
        v.setTitle("**标题**\n[副标题](https://example.com)");
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(v);
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals("标题 副标题", payload.get("title"));
    }

    @Test
    void 清洗后没有任何内容时不创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("<script>alert(1)</script>"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.createJob(88L, createRequest(), 7L));
        assertTrue(ex.getMessage().contains("正文内容为空"), ex.getMessage());
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void 原始版本对象不会被清洗逻辑修改() {
        ArticleVersionEntity v = version("# 原文\n\n**保留** [链接](https://example.com)");
        v.setTitle("**原标题**");
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(v);
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);

        service.createJob(88L, createRequest(), 7L);

        assertEquals("# 原文\n\n**保留** [链接](https://example.com)", v.getContentMd());
        assertEquals("**原标题**", v.getTitle());
    }

    @Test
    void agent结果成功只写BYD字段不触碰公众号字段() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(true);
        req.setDraftId("draft-id");
        req.setDraftCount(18);

        Map<String, Object> result = service.reportResult("job-id", req);
        assertEquals("SUCCESS", result.get("status"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("byd_dynasty_last_job_id"));
        assertTrue(sqlSet.contains("byd_dynasty_published_at"));
        String publicSqlSet = sqlSet.replaceAll("(?i)byd_dynasty_[a-z_]+", "");
        assertFalse(publicSqlSet.contains("publish_media_id"));
        assertFalse(publicSqlSet.contains("publish_theme"));
        assertFalse(publicSqlSet.contains("published_at"));
        assertFalse(publicSqlSet.contains("status"));
    }

    @Test
    void 其他agent不能重复上报同一任务() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        job.setAgentId(99L);
        when(jobMapper.selectOne(any())).thenReturn(job);

        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(true);
        assertThrows(IllegalStateException.class, () -> service.reportResult("job-id", req));
        verify(jobMapper, never()).update(isNull(), any());
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void 状态查询使用当前登录用户id而不是项目createdBy字符串() {
        ArticleProjectEntity p = project();
        p.setCreatedBy("admin");
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.state(88L, 7L);

        assertEquals(Boolean.TRUE, result.get("agentOnline"));
        assertEquals("local-win", result.get("agentId"));
        assertEquals(Boolean.TRUE, result.get("agentPreflightReady"));
    }

    @Test
    void state返回完整成对选项并忽略半套项目历史() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.state(88L, 7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) result.get("clubTagOptions");
        assertEquals(1, options.size());
        assertEquals("220", options.get(0).get("clubId"));
        assertEquals("四川迪友会", options.get(0).get("clubTitle"));
        assertEquals("220", result.get("clubId"));
        assertEquals("四川迪友会", result.get("clubTitle"));
    }

    @Test
    void state包含完整项目历史对并以项目对为初始选择() {
        ArticleProjectEntity p = project();
        p.setBydDynastyClubId("330");
        p.setBydDynastyClubTitle("项目车友会");
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.state(88L, 7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) result.get("clubTagOptions");
        assertEquals(2, options.size());
        assertEquals(Map.of("clubId", "220", "clubTitle", "四川迪友会"), options.get(0));
        assertEquals(Map.of("clubId", "330", "clubTitle", "项目车友会"), options.get(1));
        assertEquals("330", result.get("clubId"));
        assertEquals("项目车友会", result.get("clubTitle"));
    }

    @Test
    void state选项按完整对去重() {
        ArticleProjectEntity p = project();
        p.setBydDynastyClubId("220");
        p.setBydDynastyClubTitle("四川迪友会");
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.state(88L, 7L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) result.get("clubTagOptions");
        assertEquals(1, options.size());
    }

    @Test
    void 请求未指定时完整项目对优先于默认对() {
        ArticleProjectEntity p = project();
        p.setBydDynastyClubId("330");
        p.setBydDynastyClubTitle("项目车友会");
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, new BydPushJobCreateRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals("330", payload.get("clubId"));
        assertEquals("项目车友会", payload.get("clubTitle"));
    }

    @Test
    void 项目对不完整时回退到完整默认对() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, new BydPushJobCreateRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals("220", payload.get("clubId"));
        assertEquals("四川迪友会", payload.get("clubTitle"));
    }

    @Test
    void 项目与默认对都不完整时不生成半套字段() {
        props.setDefaultClubId(null);
        props.setDefaultClubTitle(null);
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, new BydPushJobCreateRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertTrue(payload.containsKey("clubId"));
        assertTrue(payload.containsKey("clubTitle"));
        assertNull(payload.get("clubId"));
        assertNull(payload.get("clubTitle"));
    }

    @Test
    void 显式不使用车友会标签时不回退项目或默认标签() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        req.setUseClubTag(false);

        Map<String, Object> data = service.createJob(88L, req, 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertNull(payload.get("clubId"));
        assertNull(payload.get("clubTitle"));
    }

    @Test
    void 显式使用车友会标签但未提供成对字段时返回中文400且不创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        req.setUseClubTag(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, req, 7L));

        assertTrue(ex.getMessage().contains("必须提供完整"));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void 显式不使用车友会标签但提供成对字段时返回中文400且不创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        BydPushJobCreateRequest req = createRequest();
        req.setUseClubTag(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, req, 7L));

        assertTrue(ex.getMessage().contains("不能再提供标签字段"));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void legacy客户端未传useClubTag时保持项目优先语义() {
        ArticleProjectEntity p = project();
        p.setBydDynastyClubId("330");
        p.setBydDynastyClubTitle("项目车友会");
        when(projectMapper.selectById(88L)).thenReturn(p);
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        assertNull(req.getUseClubTag());

        Map<String, Object> data = service.createJob(88L, req, 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals("330", payload.get("clubId"));
        assertEquals("项目车友会", payload.get("clubTitle"));
    }

    @Test
    void 请求只提供半套车友会标签时返回中文400且不创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));

        BydPushJobCreateRequest onlyId = new BydPushJobCreateRequest();
        onlyId.setClubId("220");
        IllegalArgumentException idError = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, onlyId, 7L));
        assertTrue(idError.getMessage().contains("必须成对选择"));

        BydPushJobCreateRequest onlyTitle = new BydPushJobCreateRequest();
        onlyTitle.setClubTitle("四川迪友会");
        IllegalArgumentException titleError = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, onlyTitle, 7L));
        assertTrue(titleError.getMessage().contains("必须成对选择"));

        verify(agentMapper, never()).selectList(any());
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void 请求成对标签不在可用选项时返回中文400且不创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("正文"));
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        req.setClubId("999");
        req.setClubTitle("未知车友会");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createJob(88L, req, 7L));

        assertTrue(ex.getMessage().contains("不可用"));
        verify(agentMapper, never()).selectList(any());
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    @Test
    void 推送成功按同一v2payload成对回写项目标签() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        job.setPayloadJson("{\"payloadVersion\":2,\"clubId\":\"330\",\"clubTitle\":\"项目车友会\"}");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(true);

        service.reportResult("job-id", req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("byd_dynasty_club_id"));
        assertTrue(sqlSet.contains("byd_dynasty_club_title"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("330"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("项目车友会"));
    }

    @Test
    void 推送成功且本次不使用标签时清空项目历史对() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        job.setPayloadJson("{\"payloadVersion\":2,\"clubId\":null,\"clubTitle\":null}");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(true);

        service.reportResult("job-id", req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("byd_dynasty_club_id"), sqlSet);
        assertTrue(sqlSet.contains("byd_dynasty_club_title"), sqlSet);
        long nullValues = captor.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value == null).count();
        assertTrue(nullValues >= 3, captor.getValue().getParamNameValuePairs().toString());
    }

    @Test
    void 推送失败不修改项目历史标签() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        job.setPayloadJson("{\"payloadVersion\":2,\"clubId\":\"330\",\"clubTitle\":\"项目车友会\"}");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(false);
        req.setError("草稿写入失败");

        service.reportResult("job-id", req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertFalse(sqlSet.contains("byd_dynasty_club_id"));
        assertFalse(sqlSet.contains("byd_dynasty_club_title"));
    }

    @Test
    void legacyV1成功任务不回写或清空项目标签() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = runningJob();
        job.setPayloadJson("{\"payloadVersion\":1,\"title\":\"旧任务\",\"messages\":[]}");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);
        BydAgentResultRequest req = new BydAgentResultRequest();
        req.setAgentId("local-win");
        req.setSuccess(true);

        service.reportResult("job-id", req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertFalse(sqlSet.contains("byd_dynasty_club_id"));
        assertFalse(sqlSet.contains("byd_dynasty_club_title"));
    }

    @Test
    void agent领取采用条件更新防止并发重复领取() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        BydDynastyPushJobEntity pending = new BydDynastyPushJobEntity();
        pending.setId(12L);
        pending.setJobId("job-id");
        pending.setStatus("PENDING");
        BydDynastyPushJobEntity running = runningJob();
        running.setId(12L);
        when(jobMapper.selectList(any())).thenReturn(List.of(pending));
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(jobMapper.selectById(12L)).thenReturn(running);

        Map<String, Object> result = service.claimJob(request());
        assertEquals(Boolean.TRUE, result.get("claimed"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<BydDynastyPushJobEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(jobMapper).update(isNull(), captor.capture());
        String segment = captor.getValue().getSqlSegment();
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(segment.contains("status"));
        assertTrue(segment.contains("agent_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("RUNNING"));
        assertTrue(sqlSet.contains("claimed_at"));
    }

    private void assertMediaFailure(RuntimeException failure) {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](sparkora-byd-media:bad_media)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(mediaService.requireReadyForPush(88L, "bad_media", BydDynastyMediaService.TYPE_IMAGE))
                .thenThrow(failure);

        RuntimeException ex = assertThrows(failure.getClass(),
                () -> service.createJob(88L, createRequest(), 7L));
        assertTrue(ex.getMessage().contains(failure.getMessage()));
        verify(mediaService, never()).extendLifetimeForJob(anyList(), any(LocalDateTime.class));
        verify(jobMapper, never()).insert(any(BydDynastyPushJobEntity.class));
        verify(projectMapper, never()).update(isNull(), any());
    }

    private com.sparkora.web.dto.BydAgentClaimRequest request() {
        var req = new com.sparkora.web.dto.BydAgentClaimRequest();
        req.setAgentId("local-win");
        return req;
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(88L);
        p.setChannel("byd_miniapp");
        p.setStatus("VERSIONS_READY");
        p.setCurrentVersionId(99L);
        p.setCreatedBy("7");
        p.setBydDynastyClubId("old");
        return p;
    }

    private ArticleVersionEntity version(String content) {
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(99L);
        v.setTitle("文章标题");
        v.setContentMd(content);
        v.setCoverImageId(3L);
        return v;
    }

    private BydDynastyAgentEntity onlineAgent() {
        BydDynastyAgentEntity a = new BydDynastyAgentEntity();
        a.setId(3L);
        a.setAgentId("local-win");
        a.setOwnerUserId(7L);
        a.setStatus("ONLINE");
        a.setLastSeenAt(LocalDateTime.now());
        a.setLastPreflightJson("{\"ready\":true}");
        return a;
    }

    private BydDynastyPushJobEntity runningJob() {
        BydDynastyPushJobEntity job = new BydDynastyPushJobEntity();
        job.setId(11L);
        job.setJobId("job-id");
        job.setProjectId(88L);
        job.setAgentId(3L);
        job.setStatus("RUNNING");
        return job;
    }

    private BydDynastyMediaEntity image(String mediaId, String sourceKind) {
        BydDynastyMediaEntity media = media(mediaId, BydDynastyMediaService.TYPE_IMAGE);
        media.setSourceKind(sourceKind);
        media.setMimeType("image/jpeg");
        media.setFileName("photo.jpg");
        media.setByteSize(BydDynastyMediaService.SOURCE_URL.equals(sourceKind) ? 0L : 345_678L);
        if (BydDynastyMediaService.SOURCE_URL.equals(sourceKind)) {
            media.setSourceUrl("https://images.example/gallery.jpg");
        } else {
            media.setStoragePath("88/photo.jpg");
        }
        return media;
    }

    private BydDynastyMediaEntity video(String mediaId) {
        BydDynastyMediaEntity media = media(mediaId, BydDynastyMediaService.TYPE_VIDEO);
        media.setSourceKind(BydDynastyMediaService.SOURCE_STAGED);
        media.setMimeType("video/mp4");
        media.setFileName("clip.mp4");
        media.setByteSize(12_345_678L);
        media.setStoragePath("88/clip.mp4");
        media.setDurationSeconds(new BigDecimal("12.300"));
        return media;
    }

    private BydDynastyMediaEntity media(String mediaId, String type) {
        BydDynastyMediaEntity media = new BydDynastyMediaEntity();
        media.setId((long) Math.abs(mediaId.hashCode()) + 1);
        media.setMediaId(mediaId);
        media.setProjectId(88L);
        media.setMediaType(type);
        media.setStorageState(BydDynastyMediaService.STATE_READY);
        media.setWidth(1200);
        media.setHeight(800);
        media.setExpiresAt(LocalDateTime.now().plusHours(1));
        return media;
    }

    private BydPushJobCreateRequest createRequest() {
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        req.setClubId("220");
        req.setClubTitle("四川迪友会");
        return req;
    }
}
