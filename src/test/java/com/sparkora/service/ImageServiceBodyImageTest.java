package com.sparkora.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparkora.ai.AiImageClient;
import com.sparkora.config.ImageProperties;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.domain.entity.ArticleVersionImageEntity;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionImageMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本-正文插图关联表读写单测（P1-⑦ body_image_ids 规范化，Mockito 不连库）。
 *
 * 语义：
 *  - `modifyBodyImage` add → 关联表 insert（sort_order = max+1 追加），已存在则幂等跳过；
 *  - add 时图片不存在 → 400；
 *  - remove → 精确 delete（不存在也视为成功）；
 *  - `delete` 引用检查改精确 SQL：id=5 不再因 15/51 误匹配；被封面/插图引用时仍拒绝；
 *  - `projectImages.bodyImageIds` 顺序与关联表查询一致（对外契约不变）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceBodyImageTest {

    @Mock ImageProperties imageProps;
    @Mock ImageAssetMapper imageMapper;
    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleVersionImageMapper versionImageMapper;
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
                versionImageMapper, aiImageClient, imageStorage, qiniuProps, tagService, newsMapper, embeddingService);
    }

    private void stubCurrentVersion(Long versionId) {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(1L);
        p.setCurrentVersionId(versionId);
        when(projectMapper.selectById(1L)).thenReturn(p);
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(versionId);
        v.setProjectId(1L);
        when(versionMapper.selectById(versionId)).thenReturn(v);
    }

    @Test
    void add_追加到末尾_sortOrder为max加1() {
        stubCurrentVersion(9L);
        when(imageMapper.selectById(7L)).thenReturn(new ImageAssetEntity());
        when(versionImageMapper.selectCount(any())).thenReturn(0L);
        when(versionImageMapper.maxSortOrder(9L)).thenReturn(2);

        service.modifyBodyImage(1L, 7L, "add");

        ArgumentCaptor<ArticleVersionImageEntity> cap = ArgumentCaptor.forClass(ArticleVersionImageEntity.class);
        verify(versionImageMapper).insert(cap.capture());
        assertEquals(9L, cap.getValue().getVersionId());
        assertEquals(7L, cap.getValue().getImageId());
        assertEquals(3, cap.getValue().getSortOrder(), "追加到末尾：max(2)+1=3");
    }

    @Test
    void add_空表_sortOrder从0起() {
        stubCurrentVersion(9L);
        when(imageMapper.selectById(7L)).thenReturn(new ImageAssetEntity());
        when(versionImageMapper.selectCount(any())).thenReturn(0L);
        when(versionImageMapper.maxSortOrder(9L)).thenReturn(null);

        service.modifyBodyImage(1L, 7L, "add");

        ArgumentCaptor<ArticleVersionImageEntity> cap = ArgumentCaptor.forClass(ArticleVersionImageEntity.class);
        verify(versionImageMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getSortOrder());
    }

    @Test
    void add_已存在_幂等不重插且不重排() {
        stubCurrentVersion(9L);
        when(imageMapper.selectById(7L)).thenReturn(new ImageAssetEntity());
        when(versionImageMapper.selectCount(any())).thenReturn(1L);

        service.modifyBodyImage(1L, 7L, "add");

        verify(versionImageMapper, never()).insert(any(ArticleVersionImageEntity.class));
        verify(versionImageMapper, never()).maxSortOrder(anyLong());
    }

    @Test
    void add_图片不存在_抛400() {
        stubCurrentVersion(9L);
        when(imageMapper.selectById(7L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.modifyBodyImage(1L, 7L, "add"));
        verify(versionImageMapper, never()).insert(any(ArticleVersionImageEntity.class));
    }

    @Test
    void add_并发UNIQUE冲突_静默视为成功() {
        stubCurrentVersion(9L);
        when(imageMapper.selectById(7L)).thenReturn(new ImageAssetEntity());
        when(versionImageMapper.selectCount(any())).thenReturn(0L);
        when(versionImageMapper.maxSortOrder(9L)).thenReturn(4);
        // 检查通过后他请求先插入（check-then-insert 竞态），UNIQUE 兜底捕 DuplicateKeyException
        when(versionImageMapper.insert(any(ArticleVersionImageEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate key"));

        assertDoesNotThrow(() -> service.modifyBodyImage(1L, 7L, "add"));
    }

    @Test
    void remove_精确删除_不存在也成功() {
        stubCurrentVersion(9L);

        service.modifyBodyImage(1L, 7L, "remove");

        verify(versionImageMapper).deleteByVersionAndImage(9L, 7L);
        verify(versionImageMapper, never()).insert(any(ArticleVersionImageEntity.class));
    }

    @Test
    void action非法_抛400() {
        stubCurrentVersion(9L);
        assertThrows(IllegalArgumentException.class, () -> service.modifyBodyImage(1L, 7L, "delete"));
    }

    @Test
    void 引用检查_id5不被15_51误匹配_可删除() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(5L);
        when(imageMapper.selectById(5L)).thenReturn(img);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());     // 无封面引用
        when(versionImageMapper.findVersionIdsByImage(5L)).thenReturn(List.of());    // 无插图引用（15/51 不再误匹配）

        service.delete(5L);

        verify(imageMapper).deleteById(5L);
    }

    @Test
    void 引用检查_被插图引用_拒绝删除() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(5L);
        img.setStorageKey("k");
        when(imageMapper.selectById(5L)).thenReturn(img);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(versionImageMapper.findVersionIdsByImage(5L)).thenReturn(List.of(9L));
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(9L);
        v.setProjectId(1L);
        when(versionMapper.selectBatchIds(List.of(9L))).thenReturn(List.of(v));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.delete(5L));
        assertTrue(ex.getMessage().contains("项目#1版本#9"), "提示应含引用方: " + ex.getMessage());
        verify(imageMapper, never()).deleteById(anyLong());
    }

    @Test
    void 引用检查_被封面引用_拒绝删除() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(5L);
        when(imageMapper.selectById(5L)).thenReturn(img);
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(9L);
        v.setProjectId(1L);
        v.setCoverImageId(5L);
        when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(v));
        when(versionImageMapper.findVersionIdsByImage(5L)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.delete(5L));
        verify(imageMapper, never()).deleteById(anyLong());
    }

    @Test
    void 快照_bodyImageIds按关联表顺序返回_契约不变() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(1L);
        p.setCurrentVersionId(9L);
        when(projectMapper.selectById(1L)).thenReturn(p);
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(9L);
        v.setProjectId(1L);
        v.setCoverImageId(3L);
        when(versionMapper.selectById(9L)).thenReturn(v);
        // 关联表有序：先 7 后 5
        when(versionImageMapper.findImageIdsByVersion(9L)).thenReturn(List.of(7L, 5L));
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of());
        when(qiniuProps.getIfAvailable()).thenReturn(null);

        Map<String, Object> m = service.projectImages(1L);

        @SuppressWarnings("unchecked")
        List<Long> bodyImageIds = (List<Long>) m.get("bodyImageIds");
        assertEquals(List.of(7L, 5L), bodyImageIds, "顺序应与关联表 sort_order 一致");
    }
}
