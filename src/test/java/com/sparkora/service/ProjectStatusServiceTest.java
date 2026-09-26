package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectStatusService 状态机转换单测(09-27-state-machine-service AC4/AC5,Mockito 不连库)。
 *
 * <p>对每个转换方法断言与 P0 修复后实现逐项等价:WHERE 状态白名单、SET 列与取值、
 * 首版 current 两拆分顺序、错误列截断口径(1000/990)、409 提示语。
 *
 * <p>断言手段:Mockito 捕获 UpdateWrapper,物化 {@code getSqlSegment()}(触发 paramNameValuePairs
 * 装载)后核对 SQL 片段与参数值——与 BriefServiceTest 同款模式。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectStatusServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long BRIEF_ID = 2L;
    private static final Long VERSION_ID = 3L;

    @Mock ArticleProjectMapper projectMapper;

    ProjectStatusService service;

    @BeforeEach
    void setUp() {
        service = new ProjectStatusService(projectMapper);
        when(projectMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    private ArticleProjectEntity project(String status) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(PROJECT_ID);
        p.setStatus(status);
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    /** 捕获项目表 UpdateWrapper(本服务项目写入一律 UpdateWrapper 条件更新)。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private UpdateWrapper<ArticleProjectEntity> capturedWrapper(int index) {
        ArgumentCaptor<UpdateWrapper<ArticleProjectEntity>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(projectMapper, times(index + 1)).update(isNull(), captor.capture());
        UpdateWrapper<ArticleProjectEntity> uw = captor.getAllValues().get(index);
        uw.getSqlSegment();   // 先物化 WHERE,paramNameValuePairs 才含条件值
        return uw;
    }

    // ==================== 常量与判定 ====================

    @Test
    void 陈旧阈值_唯一定义_10分钟() {
        assertEquals(10 * 60 * 1000L, ProjectStatusService.STALE_GENERATING_MS);
    }

    @Test
    void 生成中且未过期_stuck为真() {
        ArticleProjectEntity p = project("GENERATING_BRIEF");
        p.setUpdatedAt(LocalDateTime.now().minusMinutes(9));
        assertTrue(service.stuckGenerating(p), "生成中未过期应判定为卡住");
    }

    @Test
    void 生成中但已过期_stuck为假_放行自愈() {
        ArticleProjectEntity p = project("GENERATING_BRIEF");
        p.setUpdatedAt(LocalDateTime.now().minusMinutes(11));
        assertFalse(service.stuckGenerating(p), "陈旧生成中应放行自愈");
    }

    @Test
    void 非生成中状态_stuck为假() {
        assertFalse(service.stuckGenerating(project("DRAFT")));
        assertFalse(service.stuckGenerating(project("READY")));
        assertFalse(service.stuckGenerating(project("VERSIONS_READY")));
    }

    @Test
    void 守卫提示语_生成中与下游两种口径() {
        assertEquals("该项目正在生成中，请稍候（刷新页面可查看进度）",
                ProjectStatusService.guardMsg(project("GENERATING_BRIEF"), "生成简报"));
        assertEquals("项目状态为「VERSIONS_READY」，生成简报仅在对应前置状态可用；下游步骤已触发，不支持回退重做",
                ProjectStatusService.guardMsg(project("VERSIONS_READY"), "生成简报"));
    }

    // ==================== 抢占 ====================

    @Test
    void 简报抢占_白名单DRAFT_READY_陈旧分支限定生成中_清last_brief_error() {
        service.claimBriefGenerating(PROJECT_ID, project("READY"), "生成简报");

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String sqlSet = uw.getSqlSet();
        assertTrue(sqlSet.contains("status"), "set 含 status");
        assertTrue(sqlSet.contains("last_brief_error"), "set 含 last_brief_error(抢占时清空)");
        assertTrue(sqlSet.contains("updated_at"), "set 含 updated_at");

        String seg = uw.getSqlSegment();
        assertTrue(seg.contains("id"), "WHERE 限定 id");
        assertTrue(seg.contains("updated_at"), "陈旧分支限定 updated_at");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("GENERATING_BRIEF"), "抢占置 GENERATING_BRIEF");
        assertTrue(values.contains("DRAFT"), "白名单含 DRAFT");
        assertTrue(values.contains("READY"), "白名单含 READY");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")),
                "陈旧自愈分支限定生成中状态");
    }

    @Test
    void 版本抢占_白名单READY_VERSIONS_READY_清last_version_error() {
        service.claimVersionsGenerating(PROJECT_ID, project("READY"), "生成版本");

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        assertTrue(uw.getSqlSet().contains("last_version_error"), "set 含 last_version_error(抢占时清空)");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("GENERATING_VERSIONS"), "抢占置 GENERATING_VERSIONS");
        assertTrue(values.contains("READY"), "白名单含 READY(首生成或追加)");
        assertTrue(values.contains("VERSIONS_READY"), "白名单含 VERSIONS_READY");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")),
                "陈旧自愈分支限定生成中状态");
    }

    @Test
    void 抢占失败_claimed为0_抛409语义提示语() {
        when(projectMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ArticleProjectEntity p = project("PUBLISHED_DRAFT");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.claimVersionsGenerating(PROJECT_ID, p, "生成版本"));
        assertEquals(ProjectStatusService.guardMsg(p, "生成版本"), ex.getMessage(), "409 提示语逐字不变");
    }

    // ==================== 成功推进 ====================

    @Test
    void 简报成功推进_仅GENERATING_BRIEF到READY_设current清错误() {
        service.advanceReady(PROJECT_ID, BRIEF_ID, Map.of());

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String sqlSet = uw.getSqlSet();
        assertTrue(sqlSet.contains("current_brief_id"), "set 含 current_brief_id");
        assertTrue(sqlSet.contains("status"), "set 含 status");
        assertTrue(sqlSet.contains("last_brief_error"), "set 含 last_brief_error(成功清空)");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("GENERATING_BRIEF"), "仅从 GENERATING_BRIEF 推进(防回退)");
        assertTrue(values.contains("READY"), "推进到 READY");
        assertTrue(values.contains(BRIEF_ID), "currentBriefId 指向本次简报");
    }

    @Test
    void 简报成功推进_extraCols业务列同条写入() {
        service.advanceReady(PROJECT_ID, BRIEF_ID, Map.of("imitation_analysis", "{\"genre\":\"评测\"}"));

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        assertTrue(uw.getSqlSet().contains("imitation_analysis"), "extraCols 业务列同条 UPDATE 写入");
        assertTrue(uw.getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.contains("评测")), "业务列取值正确");
        verify(projectMapper, times(1)).update(isNull(), any(Wrapper.class));   // 业务列不拆第二条 UPDATE
    }

    @Test
    void 多版本成功_首版两拆分_第一条含current_命中即不再发第二条() {
        service.advanceVersionsReady(PROJECT_ID, VERSION_ID, "部分版本失败: [B:风] 超时");

        UpdateWrapper<ArticleProjectEntity> first = capturedWrapper(0);
        assertTrue(first.getSqlSet().contains("current_version_id"), "第一条 set 含 current_version_id");
        String seg = first.getSqlSegment();
        assertTrue(seg.contains("current_version_id"), "第一条 WHERE 含 isNull(current_version_id) 首版判定");
        var values = first.getParamNameValuePairs().values();
        assertTrue(values.contains(VERSION_ID), "首版 id 写入 current");
        assertTrue(values.contains("VERSIONS_READY"), "推进到 VERSIONS_READY");
        assertTrue(values.contains("部分版本失败: [B:风] 超时"), "partialErrors 写入 last_version_error");
        verify(projectMapper, times(1)).update(isNull(), any(Wrapper.class));   // 命中首条即返回,不发第二条
    }

    @Test
    void 多版本成功_首版未命中_第二条只推状态不覆盖current() {
        when(projectMapper.update(isNull(), any(Wrapper.class))).thenReturn(0, 1);

        service.advanceVersionsReady(PROJECT_ID, VERSION_ID, null);

        UpdateWrapper<ArticleProjectEntity> second = capturedWrapper(1);
        String sqlSet = second.getSqlSet();
        assertFalse(sqlSet.contains("current_version_id"), "第二条不覆盖用户已选 current");
        assertTrue(sqlSet.contains("last_version_error"), "第二条仍写 last_version_error(null 清空)");
        var values = second.getParamNameValuePairs().values();
        assertTrue(values.contains("GENERATING_VERSIONS"), "第二条仍限定 GENERATING_VERSIONS(防回退)");
        assertTrue(values.contains("VERSIONS_READY"), "第二条推进状态");
        verify(projectMapper, times(2)).update(isNull(), any(Wrapper.class));   // 两拆分共两条 UPDATE
    }

    @Test
    void 深度版本抢占_白名单READY_DRAFT_VERSIONS_READY_清last_version_error() {
        service.claimDeepVersionsGenerating(PROJECT_ID, project("DRAFT"), "生成版本");

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        assertTrue(uw.getSqlSet().contains("last_version_error"), "set 含 last_version_error(抢占时清空)");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("GENERATING_VERSIONS"), "抢占置 GENERATING_VERSIONS");
        assertTrue(values.contains("READY"), "白名单含 READY");
        assertTrue(values.contains("DRAFT"), "白名单含 DRAFT(跳过简报直接生成)");
        assertTrue(values.contains("VERSIONS_READY"), "白名单含 VERSIONS_READY(追加)");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")),
                "陈旧自愈分支限定生成中状态");
    }

    @Test
    void 发布成功_任意态置PUBLISHED_DRAFT_set发布列清错误() {
        LocalDateTime now = LocalDateTime.now();
        service.markPublished(PROJECT_ID, "MEDIA123", "orange", now);

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String sqlSet = uw.getSqlSet();
        assertTrue(sqlSet.contains("publish_media_id"), "set 含 publish_media_id");
        assertTrue(sqlSet.contains("publish_theme"), "set 含 publish_theme");
        assertTrue(sqlSet.contains("published_at"), "set 含 published_at");
        assertTrue(sqlSet.contains("last_publish_error"), "set 含 last_publish_error(成功清空)");
        String seg = uw.getSqlSegment();
        assertTrue(seg.contains("id") && !seg.contains("status"), "任意态可重发覆盖,WHERE 无状态白名单");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("PUBLISHED_DRAFT"), "推进到 PUBLISHED_DRAFT");
        assertTrue(values.contains("MEDIA123"), "media_id 落库");
        assertTrue(values.contains("orange"), "theme 落库");
        assertTrue(values.contains(now), "publishedAt 同一时间戳落库");
    }

    // ==================== 失败回退与错误列 ====================

    @Test
    void 简报失败_仅生成中回DRAFT_错误截断1000() {
        service.failBriefToDraft(PROJECT_ID, "x".repeat(1500));

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String sqlSet = uw.getSqlSet();
        assertTrue(sqlSet.contains("last_brief_error"), "set 含 last_brief_error");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("DRAFT"), "回退到 DRAFT");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")),
                "回退限定生成中状态(防覆盖并发推进)");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.length() == 1000),
                "错误截断到 1000 字符");
    }

    @Test
    void 版本失败_仅生成中回READY_错误截断1000() {
        service.failVersionsToReady(PROJECT_ID, "y".repeat(1200));

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        assertTrue(uw.getSqlSet().contains("last_version_error"), "set 含 last_version_error");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.contains("READY"), "回退到 READY");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.startsWith("GENERATING")),
                "回退限定生成中状态");
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.length() == 1000),
                "错误截断到 1000 字符");
    }

    @Test
    void 发布失败_不动状态_仅写last_publish_error_压缩截断990() {
        service.markPublishFailure(PROJECT_ID, "渲染\n失败    超时 " + "z".repeat(1200));

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String sqlSet = uw.getSqlSet();
        assertFalse(sqlSet.contains("status"), "发布失败不动状态(可重试)");
        assertTrue(sqlSet.contains("last_publish_error"), "set 含 last_publish_error");
        String seg = uw.getSqlSegment();
        assertTrue(seg.contains("id") && !seg.contains("status"), "WHERE 无状态条件");
        var values = uw.getParamNameValuePairs().values();
        String saved = values.stream().filter(v -> v instanceof String s && s.length() == 991)
                .map(v -> (String) v).findFirst().orElse(null);
        assertTrue(saved != null && saved.endsWith("…") && !saved.contains("\n"),
                "压缩空白+截断 990 加省略号");
    }

    @Test
    void 发布失败_落库异常_吞掉不抛() {
        when(projectMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow_service(() -> service.markPublishFailure(PROJECT_ID, "boom"));
    }

    @Test
    void 清空brief错误_null时_仅当已有错误才发UPDATE() {
        service.writeBriefError(PROJECT_ID, null);

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        String seg = uw.getSqlSegment();
        assertTrue(seg.contains("last_brief_error"), "清空分支 WHERE 含 isNotNull(last_brief_error) 优化");
        assertFalse(uw.getSqlSet().contains("status"), "单列写入不触碰状态");
        // set 语义:last_brief_error = #{...}(值参数不在 paramNameValuePairs 中存 null,由 SQL 片断证置空)
        assertTrue(uw.getSqlSet().startsWith("last_brief_error"), "set 第一列为 last_brief_error(置空)");
    }

    @Test
    void 写入brief错误_非null_按id写入截断1000() {
        service.writeBriefError(PROJECT_ID, "w".repeat(1100));

        UpdateWrapper<ArticleProjectEntity> uw = capturedWrapper(0);
        assertFalse(uw.getSqlSegment().contains("last_brief_error IS NOT NULL"),
                "写分支不做 isNotNull 判定(无条件覆写)");
        var values = uw.getParamNameValuePairs().values();
        assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.length() == 1000),
                "写入截断到 1000 字符");
        assertFalse(uw.getSqlSet().contains("status"), "单列写入不触碰状态");
    }

    /** markPublishFailure 吞异常的断言包装。 */
    private static void assertDoesNotThrow_service(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new AssertionError("发布失败落库异常不应外抛", e);
        }
    }
}