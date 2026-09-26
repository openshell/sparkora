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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图生图多参考图服务契约单测（09-26 img2img-multi-ref，R2/R5/R7，Mockito 不连库）：
 * 参考图数校验（1~4 及文案）、先 files 后 refImageIds 的保序合并、
 * `ref_image_id` 落值规则（仅「1 张且图库来源」落 id，其余 NULL）。
 */
@ExtendWith(MockitoExtension.class)
class ImageServiceMultiRefTest {

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

    /** 合法 PNG 魔数（readValidatedImage 会嗅探）。 */
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0};

    @BeforeEach
    void setUp() {
        service = new ImageService(imageProps, imageMapper, projectMapper, versionMapper,
                aiImageClient, imageStorage, qiniuProps, tagService, newsMapper, embeddingService);
        lenient().when(imageProps.getMaxUploadMb()).thenReturn(10);
        lenient().when(tagService.normalize(any())).thenReturn(List.of());
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "image/png", PNG);
    }

    private static ImageAssetEntity libImage(Long id, String name, String key) {
        ImageAssetEntity e = new ImageAssetEntity();
        e.setId(id);
        e.setFileName(name);
        e.setStorageKey(key);
        return e;
    }

    /** 让生成结果入库（data URL 避免网络下载）。 */
    private void stubSuccessfulGen() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
        when(aiImageClient.generateImage2Image(anyString(), any(), any(), any()))
                .thenReturn(new AiImageClient.GenResult(dataUrl, "m"));
        when(imageMapper.selectOne(any())).thenReturn(null);
        when(imageStorage.upload(any(), anyString())).thenReturn("k");
        when(imageMapper.insert(any(ImageAssetEntity.class))).thenReturn(1);
    }

    private Long capturedRefImageId() {
        ArgumentCaptor<ImageAssetEntity> cap = ArgumentCaptor.forClass(ImageAssetEntity.class);
        verify(imageMapper).insert((ImageAssetEntity) cap.capture());
        return cap.getValue().getRefImageId();
    }

    @Test
    void 零参考图_400请至少选择1张() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage2ImageFromUpload(null, List.of(), List.of(), "p", null, 1, null, "u"));
        assertEquals("请至少选择 1 张参考图", ex.getMessage());
    }

    /** 入参含 null 元素须先过滤再计数，[null] 不得绕过 0 校验（也不得进 selectById 触发 NPE）。 */
    @Test
    void 仅null元素_视为零参考图_400() {
        List<Long> ids = new ArrayList<>();
        ids.add(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage2ImageFromUpload(null, null, ids, "p", null, 1, null, "u"));
        assertEquals("请至少选择 1 张参考图", ex.getMessage());
    }

    @Test
    void 超过4张_400最多支持4张() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage2ImageFromUpload(null,
                        List.of(file("a.png"), file("b.png"), file("c.png")), List.of(1L, 2L), "p", null, 1, null, "u"));
        assertEquals("最多支持 4 张参考图", ex.getMessage());
    }

    @Test
    void 空文件_400保留既有文案() {
        MockMultipartFile empty = new MockMultipartFile("files", "a.png", "image/png", new byte[0]);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage2ImageFromUpload(null, List.of(empty), null, "p", null, 1, null, "u"));
        assertEquals("请选择要上传的参考图", ex.getMessage());
    }

    @Test
    void 图库参考图不存在_400() {
        when(imageMapper.selectById(9L)).thenReturn(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage2ImageFromUpload(null, null, List.of(9L), "p", null, 1, null, "u"));
        assertEquals("参考图不存在", ex.getMessage());
    }

    /** 先 files 后 refImageIds，后端不重排；多图结果 ref_image_id 落 null。 */
    @Test
    void files优先于图库id_且多图refImageId为null() {
        stubSuccessfulGen();
        when(imageStorage.download("key7")).thenReturn(PNG);
        when(imageMapper.selectById(7L)).thenReturn(libImage(7L, "lib.png", "key7"));

        service.generateImage2ImageFromUpload(null, List.of(file("a.png")), List.of(7L), "p", null, 1, null, "u");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<byte[]>> bytesCap = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> namesCap = ArgumentCaptor.forClass(List.class);
        verify(aiImageClient).generateImage2Image(anyString(), bytesCap.capture(), namesCap.capture(), any());
        assertEquals(2, bytesCap.getValue().size());
        assertEquals(List.of("a.png", "lib.png"), namesCap.getValue(), "顺序须为先 files 后 refImageIds");
        assertNull(capturedRefImageId(), "多图结果 ref_image_id 须为 null");
    }

    @Test
    void 仅单张图库参考图_落该id() {
        stubSuccessfulGen();
        when(imageStorage.download("key7")).thenReturn(PNG);
        when(imageMapper.selectById(7L)).thenReturn(libImage(7L, "lib.png", "key7"));

        service.generateImage2ImageFromUpload(null, null, List.of(7L), "p", null, 1, null, "u");

        assertEquals(7L, capturedRefImageId());
    }

    @Test
    void 仅单张上传文件_refImageId为null() {
        stubSuccessfulGen();

        service.generateImage2ImageFromUpload(null, List.of(file("a.png")), null, "p", null, 1, null, "u");

        assertNull(capturedRefImageId(), "上传文件未入库，无法自引用");
    }
}
