package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BYD 王朝推送契约核心单测：Agent Token、离线防误发、payload 编译、BYD 字段隔离。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BydDynastyPushServiceTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleVersionImageMapper versionImageMapper;
    @Mock ImageService imageService;
    @Mock BydDynastyAgentMapper agentMapper;
    @Mock BydDynastyPushJobMapper jobMapper;

    private BydDynastyProperties props;
    private BydDynastyPushService service;

    @BeforeEach
    void setUp() {
        props = new BydDynastyProperties();
        props.setAgentToken("agent-secret");
        props.setAgentOwnerUserId(7L);
        service = new BydDynastyPushService(projectMapper, versionMapper, versionImageMapper,
                imageService, agentMapper, jobMapper, props, new ObjectMapper());
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
    void payload清洗Markdown和HTML且不泄露本机MMKV路径() {
        String markdown = "# 第一段\n\n**粗体** 和 [链接](https://example.com/x)<br>第二行\n\n"
                + "![插图](https://qiniu.local/p1.jpg)\n";
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version(markdown));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(imageService.publicUrl(3L)).thenReturn("https://qiniu.local/cover.jpg");
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals(1, payload.get("payloadVersion"));
        assertEquals("文章标题", payload.get("title"));
        assertEquals("220", payload.get("clubId"));
        assertEquals("四川迪友会", payload.get("clubTitle"));
        assertNotNull(payload.get("draftId"));
        assertNotNull(payload.get("jobId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(3, messages.size());
        assertEquals(Map.of("type", "img", "imageRef", "COVER-3"), messages.get(0));
        assertEquals("text", messages.get(1).get("type"));
        String content = (String) messages.get(1).get("content");
        assertTrue(content.contains("第一段"), content);
        assertTrue(content.contains("粗体"), content);
        assertTrue(content.contains("链接"), content);
        assertTrue(content.contains("第二行"), content);
        assertFalse(content.contains("#"), content);
        assertFalse(content.contains("**"), content);
        assertFalse(content.contains("https://"), content);
        assertEquals(Map.of("type", "img", "imageRef", "BODY-1"), messages.get(2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) payload.get("images");
        assertEquals(2, images.size());
        assertEquals("COVER-3", images.get(0).get("id"));
        assertEquals("BODY-1", images.get(1).get("id"));
        String raw = data.toString();
        assertFalse(raw.toLowerCase().contains("mmkv"));
        assertFalse(raw.toLowerCase().contains("usrmmkvstorage"));
        verify(jobMapper).insert(any(BydDynastyPushJobEntity.class));
    }

    @Test
    void 标题经过行内清洗后写入payload() {
        ArticleVersionEntity v = version("正文");
        v.setTitle("**标题**\n[副标题](https://example.com)");
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(v);
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(imageService.publicUrl(3L)).thenReturn("https://qiniu.local/cover.jpg");
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        assertEquals("标题 副标题", payload.get("title"));
    }

    @Test
    void 只有图片的正文仍可创建任务() {
        when(projectMapper.selectById(88L)).thenReturn(project());
        when(versionMapper.selectById(99L)).thenReturn(version("![图](https://qiniu.local/only.jpg)"));
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(imageService.publicUrl(3L)).thenReturn("https://qiniu.local/cover.jpg");
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        Map<String, Object> data = service.createJob(88L, createRequest(), 7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(2, messages.size(), messages.toString());
        assertEquals(Map.of("type", "img", "imageRef", "COVER-3"), messages.get(0));
        assertEquals(Map.of("type", "img", "imageRef", "BODY-1"), messages.get(1));
        verify(jobMapper).insert(any(BydDynastyPushJobEntity.class));
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
        when(imageService.publicUrl(3L)).thenReturn("https://qiniu.local/cover.jpg");
        when(jobMapper.insert(any(BydDynastyPushJobEntity.class))).thenReturn(1);
        when(projectMapper.update(isNull(), any())).thenReturn(1);

        service.createJob(88L, createRequest(), 7L);

        assertEquals("# 原文\n\n**保留** [链接](https://example.com)", v.getContentMd());
        assertEquals("**原标题**", v.getTitle());
    }

    @Test
    void agent结果成功只写BYD字段不触碰公众号字段() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = new BydDynastyPushJobEntity();
        job.setId(11L);
        job.setJobId("job-id");
        job.setProjectId(88L);
        job.setAgentId(3L);
        job.setStatus("RUNNING");
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
        // 去掉 BYD 前缀后再检查，避免 byd_dynasty_published_at 的包含式断言误判。
        String publicSqlSet = sqlSet.replaceAll("(?i)byd_dynasty_[a-z_]+", "");
        assertFalse(publicSqlSet.contains("publish_media_id"));
        assertFalse(publicSqlSet.contains("publish_theme"));
        assertFalse(publicSqlSet.contains("published_at"));
        assertFalse(publicSqlSet.contains("status"));
    }

    @Test
    void 其他agent不能重复上报同一任务() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        BydDynastyPushJobEntity job = new BydDynastyPushJobEntity();
        job.setJobId("job-id");
        job.setAgentId(99L);
        job.setStatus("RUNNING");
        job.setProjectId(88L);
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

        Map<String, Object> result = service.state(88L, 7L);

        assertEquals(Boolean.TRUE, result.get("agentOnline"));
        assertEquals("local-win", result.get("agentId"));
        assertEquals(Boolean.TRUE, result.get("agentPreflightReady"));
    }

    @Test
    void agent领取采用条件更新防止并发重复领取() {
        when(agentMapper.selectOne(any())).thenReturn(onlineAgent());
        when(agentMapper.selectList(any())).thenReturn(List.of());
        BydDynastyPushJobEntity pending = new BydDynastyPushJobEntity();
        pending.setId(12L);
        pending.setJobId("job-id");
        pending.setStatus("PENDING");
        BydDynastyPushJobEntity running = new BydDynastyPushJobEntity();
        running.setId(12L);
        running.setJobId("job-id");
        running.setAgentId(3L);
        running.setStatus("RUNNING");
        when(jobMapper.selectList(any())).thenReturn(List.of(pending));
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(jobMapper.selectById(12L)).thenReturn(running);

        @SuppressWarnings("unchecked")
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

    private com.sparkora.web.dto.BydAgentClaimRequest request() {
        var req = new com.sparkora.web.dto.BydAgentClaimRequest();
        req.setAgentId("local-win");
        return req;
    }

    private ArticleProjectEntity project() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(88L);
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

    private BydPushJobCreateRequest createRequest() {
        BydPushJobCreateRequest req = new BydPushJobCreateRequest();
        req.setClubId("220");
        req.setClubTitle("四川迪友会");
        return req;
    }
}
