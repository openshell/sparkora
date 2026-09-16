package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.config.ImageProperties;
import com.sparkora.config.QiniuProperties;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.ai.AiImageClient;
import com.sparkora.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 去重命中时 source_ref 补写的并发安全单测（09-15 img-classify，Mockito 不连库）。
 *
 * 语义（design.md「同图被不同新闻引用：保留首次值，不覆盖」）：
 *  - 已有非空 source_ref → 不更新（历史已追溯/首次值不可被改写）；
 *  - 已有为空 → 条件 UPDATE（WHERE 含 source_ref 空判定，原子抢占，防 check-then-set 竞态）；
 *    UPDATE 命中 0 行（并发已写入）→ 以库中现有值为准回填实体，不覆盖为本次值。
 */
@ExtendWith(MockitoExtension.class)
class ImageServiceSourceRefTest {

    @Mock ImageProperties imageProps;
    @Mock ImageAssetMapper imageMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock AiImageClient aiImageClient;
    @Mock ImageStorage imageStorage;
    @Mock ObjectProvider<QiniuProperties> qiniuProps;
    @Mock ImageTagService tagService;
    @Mock ObjectProvider<com.sparkora.mapper.NewsMapper> newsMapper;
    @Mock ImageEmbeddingService embeddingService;

    ImageService service;

    @BeforeEach
    void setUp() {
        service = new ImageService(imageProps, imageMapper, projectMapper, versionMapper,
                aiImageClient, imageStorage, qiniuProps, tagService, newsMapper, embeddingService);
    }

    /** 造 preset（本次入库携带的来源串）。 */
    private static ImageAssetEntity preset(String ref) {
        ImageAssetEntity p = new ImageAssetEntity();
        p.setSourceRef(ref);
        return p;
    }

    private static ImageAssetEntity existing(Long id, String ref) {
        ImageAssetEntity t = new ImageAssetEntity();
        t.setId(id);
        t.setSourceRef(ref);
        return t;
    }

    @Test
    void 已有非空来源串_不更新_保留首次值() {
        ImageAssetEntity target = existing(7L, "/page/byd-cn/news-2026/detail632");
        when(imageMapper.selectOne(any())).thenReturn(target);   // 去重命中

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset("/page/byd-cn/news-2026/detail999"));

        assertEquals("/page/byd-cn/news-2026/detail632", out.getSourceRef(), "首次值不得被改写");
        verify(imageMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void 来源串为空_条件UPDATE含空判定_原子抢占() {
        ImageAssetEntity target = existing(7L, null);
        when(imageMapper.selectOne(any())).thenReturn(target);
        when(imageMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset("/page/byd-cn/news-2026/detail999"));

        ArgumentCaptor<Wrapper<ImageAssetEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(imageMapper).update(isNull(), captor.capture());
        UpdateWrapper<ImageAssetEntity> w = (UpdateWrapper<ImageAssetEntity>) captor.getValue();
        String sql = ((AbstractWrapper<ImageAssetEntity, ?, ?>) w).getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("source_ref"),
                "WHERE 必须含 source_ref 空判定（否则存在 check-then-set 竞态）: " + sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("IS NULL") || sql.contains("= ''"),
                "WHERE 必须判定 source_ref 为空: " + sql);
        assertEquals("/page/byd-cn/news-2026/detail999", out.getSourceRef());
    }

    @Test
    void 并发已写入_UPDATE命中0行_以库中现有值为准不回覆盖() {
        ImageAssetEntity target = existing(7L, null);
        when(imageMapper.selectOne(any())).thenReturn(target);
        when(imageMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);   // 并发窗口：已被他事务写入
        ImageAssetEntity fresh = existing(7L, "/page/byd-cn/news-2026/detail632");
        when(imageMapper.selectById(7L)).thenReturn(fresh);

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset("/page/byd-cn/news-2026/detail999"));

        assertEquals("/page/byd-cn/news-2026/detail632", out.getSourceRef(), "应以并发写入的值为准（首次值）");
    }

    @Test
    void 本次无来源串_不做任何来源更新() {
        ImageAssetEntity target = existing(7L, null);
        when(imageMapper.selectOne(any())).thenReturn(target);

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset(null));

        assertNull(out.getSourceRef());
        verify(imageMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void 本次来源串为空白_不做来源更新() {
        ImageAssetEntity target = existing(7L, null);
        when(imageMapper.selectOne(any())).thenReturn(target);

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset("   "));

        assertNull(out.getSourceRef());
        verify(imageMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /** 去重命中会走 mergeTags；标签为空时不触库。 */
    @Test
    void 去重命中_标签钩子仍生效() {
        ImageAssetEntity target = existing(7L, null);
        when(imageMapper.selectOne(any())).thenReturn(target);
        when(imageMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(tagService.tagNamesOf(7L)).thenReturn(List.of("新闻"));

        ImageAssetEntity out = service.persistOrReuse(new byte[]{1, 2, 3}, "png", preset("/page/x/detail1"));

        assertEquals(List.of("新闻"), out.getTags());
    }
}
