package com.sparkora.service;

import com.sparkora.article.illustrate.AnchorExtractor;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.ArticleVersionImageEntity;
import com.sparkora.domain.entity.IllustrationDismissEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionImageMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配图建议服务单测（09-15 article-auto-illustrate，子C；Mockito 不连库/不调 AI）。
 *
 * 覆盖：无版本/正文空/项目不存在/minScore 非法 → IllegalArgumentException；
 * 已忽略锚点被过滤；单锚点检索失败跳过 + 其余正常；无候选锚点不出现；
 * **零副作用断言**（suggest 不触发任何写路径）；dismiss 幂等与入参校验；
 * **重复配图防御**（10-11 img-suggest-dedup）：节内含图锚点不产建议、已登记关联图排除、
 * 同图跨锚点只出现在最先命中组；
 * **门槛配置解耦**（10-11 img-suggest-threshold）：缺省取建议专用 AI_ILLUSTRATION_MIN_SCORE、
 * 与图库搜索 AI_IMAGE_MIN_SCORE 互不影响、显式传参优先。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IllustrationSuggestionServiceTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleVersionImageMapper versionImageMapper;
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
        aiProps.setIllustrationMinScore(0.4);
        aiProps.setIllustrationMaxAnchors(5);
        aiProps.setIllustrationTopN(3);
        service = new IllustrationSuggestionService(projectMapper, versionMapper, versionImageMapper,
                embeddingService, dismissMapper, aiProps);
        // 默认:版本未登记任何正文插图(重复配图防御用例再按需覆盖)
        when(versionImageMapper.findImageIdsByVersion(any())).thenReturn(List.of());
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
        // 两锚点返回不同图:同一张图在 R2 语义下只会落在最先命中的锚点组
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), anyList()))
                .thenReturn(List.of(hit(7L, 0.62)))
                .thenReturn(List.of(hit(8L, 0.61)));

        List<IllustrationSuggestionService.AnchorSuggestion> out =
                service.suggest(1L, List.of("主题/销量"), 0.42);

        assertEquals(2, out.size(), "两段均可配图");
        assertEquals("", out.get(0).headingPath());
        assertEquals("续航实测", out.get(1).headingPath());
        assertEquals(0, out.get(0).anchorIndex());
        assertEquals(1, out.get(1).anchorIndex());
        assertEquals(12, out.get(0).anchorKey().length());
        assertEquals(7L, out.get(0).candidates().get(0).imageId());
        assertEquals(8L, out.get(1).candidates().get(0).imageId());
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

    // ==================== 重复配图防御（10-11 img-suggest-dedup）====================

    @Test
    void 已登记关联图被排除_不进入任何锚点候选() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        // 图 7 已登记为本版本正文插图(R3:无论 markdown 里是否仍引用,不再进入新锚点组)
        when(versionImageMapper.findImageIdsByVersion(9L)).thenReturn(List.of(7L));
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.62)));

        assertTrue(service.suggest(1L, null, null).isEmpty(), "唯一候选图已被登记占用 → 不产任何建议组");
    }

    @Test
    void 锚点节内含图_整组不出现且不触发检索() {
        String md = String.join("\n",
                "这是开头的前言段落，描述文章的背景与写作动机，长度足够参与配图建议的语义检索。",
                "",
                "## 续航实测",
                "",
                "![](https://pic.example.com/a.jpg)",
                "",
                "实测高速工况下的续航表现，空调全开时的电耗变化与官方标称数值存在明显差异，需要结合路况说明。");
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, md));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.62)));

        List<IllustrationSuggestionService.AnchorSuggestion> out = service.suggest(1L, null, null);

        assertEquals(1, out.size(), "含图锚点(R1)整组不出现");
        assertEquals("", out.get(0).headingPath(), "仅前言锚点保留");
        // 含图锚点在检索前被跳过:仅前言锚点调 1 次检索
        verify(embeddingService, times(1)).searchImages(any(), anyInt(), anyDouble(), any());
    }

    @Test
    void 同图命中两锚点_仅最先一组出现() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        // 两锚点检索返回同一张图(R2:只保留最先/相似度最高命中的锚点组)
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.62)));

        List<IllustrationSuggestionService.AnchorSuggestion> out = service.suggest(1L, null, null);

        verify(embeddingService, times(2)).searchImages(any(), anyInt(), anyDouble(), any());
        assertEquals(1, out.size(), "同一张图只出现在最先命中的锚点组");
        assertEquals("", out.get(0).headingPath());
        assertEquals(7L, out.get(0).candidates().get(0).imageId());
    }

    @Test
    void 已登记与先命中合并占用_混合场景() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        // 已登记 7(全局占用);前言锚命中 8(会话占用);续航锚再命中 7、8 → 全部被占用 → 不出现
        when(versionImageMapper.findImageIdsByVersion(9L)).thenReturn(List.of(7L));
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(hit(8L, 0.7)))
                .thenReturn(List.of(hit(7L, 0.6), hit(8L, 0.5)));

        List<IllustrationSuggestionService.AnchorSuggestion> out = service.suggest(1L, null, null);

        assertEquals(1, out.size());
        assertEquals("", out.get(0).headingPath());
        assertEquals(8L, out.get(0).candidates().get(0).imageId(), "已登记图不占用候选位,先命中的新图入选");
    }

    /**
     * 零副作用硬断言（PRD AC）：suggest 不得触发任何写路径。
     * 本服务刻意不注入 ImageService，故此处断言「无 insert/update/delete 类写调用」
     * ——只有 dismissMapper.selectList（读）、project/version 的 selectById（读）
     * 与 versionImageMapper.findImageIdsByVersion（读，10-11 img-suggest-dedup 起新增）。
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
        // ①' 只读关联表 mapper:suggest 只允许 select 类读(findImageIdsByVersion),不得触达任何写方法
        verify(versionImageMapper, never()).insert(any(ArticleVersionImageEntity.class));
        verify(versionImageMapper, never()).update(any(ArticleVersionImageEntity.class), any());
        verify(versionImageMapper, never()).delete(any());
        verify(versionImageMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(versionImageMapper, never()).deleteByVersionAndImage(any(), any());
        verify(versionImageMapper, never()).deleteByVersion(any());
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

    // ==================== 门槛配置解耦（10-11 img-suggest-threshold）====================

    /** R1:suggest 未显式传 minScore → 取建议专用配置 AI_ILLUSTRATION_MIN_SCORE;
     *  图库搜索的 AI_IMAGE_MIN_SCORE 即使设得再高也不影响缺省建议(解耦断言)。 */
    @Test
    void 缺省门槛取建议专用配置_与图库搜索配置解耦() {
        aiProps.setIllustrationMinScore(0.5);   // 可区分值:若误读图库搜索配置将透传 0.3 或 0.9
        aiProps.setImageMinScore(0.9);
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.6)));

        assertTrue(service.suggest(1L, null, null).size() >= 1, "0.6 命中高于 0.5 缺省门槛,应有建议组");

        // 两个锚点检索均透传建议专用缺省门槛(而非图库搜索的 0.9)
        verify(embeddingService, times(2)).searchImages(any(), anyInt(), eq(0.5), any());
    }

    /** 配置缺省值锁定(校准初定 0.4;真机校准调整该值时须有意识地更新此断言与 docs/spec/image.md §7)。 */
    @Test
    void 建议门槛配置默认0点4_图库搜索默认0点3不变() {
        assertEquals(0.4, new AiProperties().getIllustrationMinScore(), 1e-9);
        assertEquals(0.3, new AiProperties().getImageMinScore(), 1e-9, "图库搜索门槛口径不变");
    }

    /** R2:显式传参语义不变——合法值优先于配置缺省;越界(<0/>1)仍 IllegalArgumentException(400)。 */
    @Test
    void 显式门槛优先于配置缺省_越界仍报错() {
        aiProps.setIllustrationMinScore(0.6);
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 9L));
        when(versionMapper.selectById(9L)).thenReturn(version(9L, MD));
        when(dismissMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.searchImages(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(hit(7L, 0.6)));

        service.suggest(1L, List.of("主题/销量"), 0.42);

        // 显式 0.42 胜过配置缺省 0.6,标签透传不变
        verify(embeddingService, times(2)).searchImages(any(), anyInt(), eq(0.42), eq(List.of("主题/销量")));

        assertEquals("相似度门槛须在 0~1 之间", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, 2.0)).getMessage());
        assertEquals("相似度门槛须在 0~1 之间", assertThrows(IllegalArgumentException.class,
                () -> service.suggest(1L, null, -0.1)).getMessage());
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
