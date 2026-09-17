package com.sparkora.service;

import com.sparkora.ai.AiImageClient;
import com.sparkora.config.ImageProperties;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ArticleProjectMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图库批量只读派生单测（09-15 qa-auto-illustrate，`ImageService.loadDerived`；Mockito 不连库）。
 *
 * 语义：批量 selectBatchIds + 复用既有 fillDerived 填 url/thumbUrl；**不写任何列**（只读契约）；
 * 空入参/全 null 不查库；非七牛时 thumbUrl 降级为原图 url；不存在的 id 自然不出现（不报错）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceLoadDerivedTest {

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

    private static ImageAssetEntity image(Long id, String storageKey) {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(id);
        img.setStorageKey(storageKey);
        return img;
    }

    @Test
    void 批量填充url与thumbUrl_非七牛降级为原图() {
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of(image(84L, "images/a.jpg")));
        when(imageStorage.publicUrl("images/a.jpg")).thenReturn("http://pic/a.jpg");
        when(qiniuProps.getIfAvailable()).thenReturn(null);

        List<ImageAssetEntity> out = service.loadDerived(List.of(84L));

        assertEquals(1, out.size());
        assertEquals("http://pic/a.jpg", out.get(0).getUrl());
        assertEquals("http://pic/a.jpg", out.get(0).getThumbUrl(), "非七牛：thumbUrl 降级为原图");
    }

    @Test
    void 空入参或全null_不查库() {
        assertTrue(service.loadDerived(null).isEmpty());
        assertTrue(service.loadDerived(List.of()).isEmpty());
        assertTrue(service.loadDerived(java.util.Arrays.asList(null, null)).isEmpty());
        verify(imageMapper, never()).selectBatchIds(anyList());
    }

    @Test
    void 去重id_不重复传入() {
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of(image(84L, "k")));
        when(imageStorage.publicUrl("k")).thenReturn("http://pic/k.jpg");
        when(qiniuProps.getIfAvailable()).thenReturn(null);

        service.loadDerived(java.util.Arrays.asList(84L, 84L, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(imageMapper).selectBatchIds(cap.capture());
        assertEquals(1, cap.getValue().size());
    }

    @Test
    void 只读_不触发任何写方法() {
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of(image(84L, "k")));
        when(imageStorage.publicUrl("k")).thenReturn("http://pic/k.jpg");
        when(qiniuProps.getIfAvailable()).thenReturn(null);

        service.loadDerived(List.of(84L));

        verify(imageMapper, never()).insert(any(ImageAssetEntity.class));
        verify(imageMapper, never()).deleteById(any(Long.class));
        verify(imageStorage, never()).upload(any(), any());
    }

    @Test
    void 无storageKey_不填url不报错() {
        when(imageMapper.selectBatchIds(any())).thenReturn(List.of(image(84L, null)));
        when(qiniuProps.getIfAvailable()).thenReturn(null);

        List<ImageAssetEntity> out = service.loadDerived(List.of(84L));

        assertEquals(1, out.size());
        assertNull(out.get(0).getUrl());
    }
}
