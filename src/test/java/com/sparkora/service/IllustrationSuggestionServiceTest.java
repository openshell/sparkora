package com.sparkora.service;

import com.sparkora.article.illustrate.AnchorExtractor;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.IllustrationDismissEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.IllustrationDismissMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配图建议服务单测（09-15 article-auto-illustrate，子C；Mockito 不连库/不调 AI）。
 *
 * 覆盖：无版本/正文空/项目不存在/minScore 非法 → IllegalArgumentException；
 * 已忽略锚点被过滤；单锚点检索失败跳过 + 其余正常；无候选锚点不出现；
 * **零副作用断言**（suggest 不触发任何写路径）；dismiss 幂等与入参校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IllustrationSuggestionServiceTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ImageEmbeddingService embeddingService;
    @Mock IllustrationDismissMapper dismissMapper;

    AiProperties aiProps;
    IllustrationSuggestionService service;

    /** 一段足够长的正文（两段 + 一个二标题），确保切出锚点。 */
    private static final String MD = String.join("\n",
            "这是开头的前言段落，描述文章的背景与写作动机，长度足够参与配图建议的语义检索。",
            "",
            "## 续航实测",
            "",
            "实测高速工况下的续航表现，空调全开时的电耗变化与官方标称数值存在明显差异，需要结合路况说明。");

    @BeforeEach
    void setUp() {
        aiProps = new AiProperties();
        aiProps.setImageMinScore(0.3);
        aiProps.setIllustrationMaxAnchors(5);
        aiProps.setIllustrationTopN(3);
        service = new IllustrationSuggestionService(projectMapper, versionMapper, embeddingService,
                dismissMapper, aiProps);
    }

    private ArticleProjectEntity project(Long id, Long currentVersionId) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(id);
        p.setCurrentVersionId(currentVersionId);
        return p;
    }

    private ArticleVersionEntity version(Long id, String contentMd) {
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(id);
        v.setContentMd(contentMd);
        return v;
    }

    private static ImageSearchHit hit(long imageId, double score) {
        return new ImageSearchHit(imageId, score, "文本", "a.jpg", "byd-news", "detail1",
                "https://pic/a.jpg", "https://pic/a.jpg", List.of("主题/销量"));
    }

    // ==================== 前置校验 ====================

    @Test
    void 项目不存在_抛IllegalArgumentException() {
        when(projectMapper.selectById(1L)).thenReturn(null);
        assertEquals("项目不存在", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, null)).getMessage());
    }

    @Test
    void 无当前版本_抛IllegalArgumentException() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, null));
        assertEquals("尚未生成正文版本，无法生成配图建议", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, null)).getMessage());
    }

    @Test
    void 正文为空_抛IllegalArgumentException() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, "   "));
        assertEquals("正文为空，无法生成配图建议", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, null)).getMessage());
    }

    @Test
    void minScore非法_抛IllegalArgumentException() {
        assertEquals("相似度门槛须在 0~1 之间", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, 2.0)).getMessage());
        assertEquals("相似度门槛须在 0~1 之间", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, -0.1)).getMessage());
    }

    /** R6「可关闭」：关闭后不产生建议，且**不调 embedding**（与「禁止自动写入」是两件事）。 */
    @Test
    void 建议开关关闭_不产生建议且不调检索() {
        aiProps.setIllustrationSuggestEnabled(false);

        assertEquals("配图建议功能已关闭", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, null)).getMessage());

        verify(projectMapper, never()).selectById(any());
        verify(embeddingService, never()).searchImages(any(), anyInt(), anyDouble(), any());
    }

    /** 开关默认开启（回归：默认行为不因新增开关而改变）。 */
    @Test
    void 建议开关默认开启() {
        org.junit.jupiter.api.Assertions.assertTrue(new AiProperties().isIllustrationSuggestEnabled());
    }

    // ==================== 建议组装 ====================

    @Test
    void 按锚点分组_透传门槛与topN_无候选锚点不出现() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), anyList()))
                .thenReturn(List.of(hit(7L, 0.62)));

        List<IllustrationSuggestionService.AnchorSuggestion> out =
                service.suggest(1L, List.of("主题/销量"), 0.42);

        assertEquals(2, out.size(), "两段均可配图");
        assertEquals("", out.get(0).headingPath());
        assertEquals("续航实测", out.get(1).headingPath());
        assertEquals(0, out.get(0).anchorIndex());
        assertEquals(1, out.get(1).anchorIndex());
        assertEquals(12, out.get(0).anchorKey().length());
        assertEquals(7L, out.get(0).candidates().get(0).imageId());
        // 逐锚点检索（两个锚点各调一次）：topN=3、显式门槛 0.42、标签原样透传
        verify(embeddingService, org.mockito.Mockito.times(2))
                .searchImages(any(), eq(3), eq(0.42), eq(List.of("主题/销量")));
    }

    @Test
    void 无候选锚点不出现_门槛以下返回空() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of());

        assertTrue(service.suggest(1L, null, null).isEmpty());
    }

    @Test
    void 单锚点检索抛异常_跳过该锚点其余正常() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        // 第一个锚点（前言）抛异常，第二个锚点（续航实测）正常
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any()))
                .thenThrow(new RuntimeException("embedding 超时"))
                .thenReturn(List.of(hit(7L, 0.5)));

        List<IllustrationSuggestionService.AnchorSuggestion> out = service.suggest(1L, null, null);

        assertEquals(1, out.size(), "失败锚点被跳过，其余照常");
        assertEquals("续航实测", out.get(0).headingPath());
    }

    @Test
    void 已忽略锚点被过滤_不重复推荐() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.5)));
        // 前言锚点指纹由切分器计算，先取真实值构造 dismiss 行
        String prefaceKey = AnchorExtractor.extract(MD, 5).get(0).key();
        IllustrationDismissEntity d = new IllustrationDismissEntity();
        d.setAnchorKey(prefaceKey);
        when(dismissMapper.selectList(any())).thenReturn(List.of(d));

        List<IllustrationSuggestionService.AnchorSuggestion> out = service.suggest(1L, null, null);

        assertEquals(1, out.size());
        assertEquals("续航实测", out.get(0).headingPath());
        assertTrue(out.stream().noneMatch(s -> s.anchorKey().equals(prefaceKey)));
    }

    /**
     * 零副作用硬断言（PRD AC）：suggest 不得触发任何写路径。
     * 本服务刻意不注入 ImageService，故此处断言「无 insert/update/delete 类写调用」
     * ——只有 dismissMapper.selectList（读）与 project/version 的 selectById（读）。
     */
    @Test
    void 零副作用_suggest只读不写() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.5)));

        service.suggest(1L, null, null);

        // ① 无任何写方法可达：服务字段里不允许出现 ImageService / 写路径调用
        verify(projectMapper, never()).insert(any(ArticleProjectEntity.class));
        verify(projectMapper, never()).updateById(any(ArticleProjectEntity.class));
        verify(projectMapper, never()).update(any(ArticleProjectEntity.class), any());
        verify(projectMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(projectMapper, never()).delete(any());
        verify(versionMapper, never()).insert(any(ArticleVersionEntity.class));
        verify(versionMapper, never()).updateById(any(ArticleVersionEntity.class));
        verify(versionMapper, never()).update(any(ArticleVersionEntity.class), any());
        verify(versionMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(dismissMapper, never()).insert(any(IllustrationDismissEntity.class));
        verify(dismissMapper, never()).update(any(IllustrationDismissEntity.class), any());
        verify(dismissMapper, never()).delete(any());
        verify(embeddingService, never()).embedQuietly(any());
        verify(embeddingService, never()).embedOne(any(com.sparkora.domain.entity.ImageAssetEntity.class));
        verify(embeddingService, never()).rebuildAll();
        verify(embeddingService, never()).rebuildMissing();

        // ② 结构性保证：服务不持有 ImageService（唯一能写 body_image_ids 的组件）
        for (Field f : IllustrationSuggestionService.class.getDeclaredFields()) {
            assertTrue(!ImageService.class.equals(f.getType()),
                    "配图建议服务不得注入 ImageService（自动写入风险）: " + f.getName());
        }
    }

    // ==================== 忽略 ====================

    @Test
    void dismiss_写入忽略记录_幂等() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectCount(any())).thenReturn(0L);

        service.dismiss(1L, "abc123def456", "admin");

        ArgumentCaptor<IllustrationDismissEntity> captor = ArgumentCaptor.forClass(IllustrationDismissEntity.class);
        verify(dismissMapper).insert(captor.capture());
        IllustrationDismissEntity e = captor.getValue();
        assertNotNull(e.getCreatedAt());
        assertEquals(1L, e.getProjectId());
        assertEquals(9L, e.getVersionId());
        assertEquals("abc123def456", e.getAnchorKey());
        assertEquals("admin", e.getCreatedBy());
    }

    @Test
    void dismiss_重复忽略不报错也不重复插入() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectCount(any())).thenReturn(1L);

        service.dismiss(1L, "abc123def456", "admin");

        verify(dismissMapper, never()).insert(any(IllustrationDismissEntity.class));
    }

    @Test
    void dismiss_并发唯一冲突被吞掉() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectCount(any())).thenReturn(0L);
        when(dismissMapper.insert(any(IllustrationDismissEntity.class))).thenThrow(
                new org.springframework.dao.DuplicateKeyException("uq"));

        service.dismiss(1L, "abc123def456", "admin");   // 不抛
    }

    @Test
    void dismiss_入参校验() {
        assertEquals("锚点标识不能为空", assertThrows(IllegalArgumentException.class,
                () -> service.dismiss(1L, null, "admin")).getMessage());
        assertEquals("锚点标识不能为空", assertThrows(IllegalArgumentException.class,
                () -> service.dismiss(1L, "   ", "admin")).getMessage());
        assertEquals("锚点标识过长", assertThrows(IllegalArgumentException.class,
                () -> service.dismiss(1L, "x".repeat(201), "admin")).getMessage());
    }

    @Test
    void dismiss_无版本_抛IllegalArgumentException() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, null));
        assertEquals("尚未生成正文版本，无法忽略配图建议", assertThrows(IllegalArgumentException.class,
                () -> service.dismiss(1L, "abc", "admin")).getMessage());
    }
}
