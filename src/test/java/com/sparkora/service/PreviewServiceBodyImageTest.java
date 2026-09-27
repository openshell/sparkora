package com.sparkora.service;

import com.sparkora.config.ImageProperties;
import com.sparkora.config.WenyanProperties;
import com.sparkora.domain.entity.ArticleProjectEntity;
import com.sparkora.domain.entity.ArticleVersionEntity;
import com.sparkora.mapper.ArticleProjectMapper;
import com.sparkora.mapper.ArticleVersionImageMapper;
import com.sparkora.mapper.ArticleVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预览插图 URL 顺序单测（P1-⑦ body_image_ids 规范化，Mockito 不连库）。
 *
 * 契约：预览渲染取图顺序 = 关联表 sort_order 顺序（封面在前，插图按有序查询）。
 * 渲染输出本身不变（`buildMarkdown` 的 bodyImageUrls 参数不影响 HTML，插图落点由 contentMd 决定），
 * 故这里验证取图**顺序**与关联表查询入口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewServiceBodyImageTest {

    @Mock ArticleProjectMapper projectMapper;
    @Mock ArticleVersionMapper versionMapper;
    @Mock ArticleVersionImageMapper versionImageMapper;
    @Mock ImageService imageService;
    @Mock ImageProperties imageProps;
    @Mock WenyanProperties wenyanProps;
    @Mock WenyanServerService serverService;
    @Mock WenyanThemeCatalog themeCatalog;

    PreviewService service;

    @BeforeEach
    void setUp() {
        service = new PreviewService(projectMapper, versionMapper, versionImageMapper, imageService,
                imageProps, wenyanProps, serverService, themeCatalog);
        when(wenyanProps.getDefaultTheme()).thenReturn("default");
        when(wenyanProps.getHighlight()).thenReturn("solarized-light");
        when(wenyanProps.getCliPath()).thenReturn("definitely-not-a-real-cmd-xyz");   // 渲染失败 → 降级保底
    }

    @Test
    void 插图URL按关联表顺序取_封面在前() {
        ArticleProjectEntity p = new ArticleProjectEntity();
        p.setId(1L);
        p.setCurrentVersionId(9L);
        p.setStatus("VERSIONS_READY");
        when(projectMapper.selectById(1L)).thenReturn(p);
        ArticleVersionEntity v = new ArticleVersionEntity();
        v.setId(9L);
        v.setContentMd("正文");
        v.setCoverImageId(3L);
        when(versionMapper.selectById(9L)).thenReturn(v);
        // 关联表有序：先 7 后 5
        when(versionImageMapper.findImageIdsByVersion(9L)).thenReturn(List.of(7L, 5L));
        when(imageService.publicUrl(any())).thenReturn("http://pic/x.jpg");

        service.preview(1L, null, null, null, null);

        verify(versionImageMapper).findImageIdsByVersion(9L);
        InOrder order = inOrder(imageService);
        order.verify(imageService).publicUrl(3L);   // 封面
        order.verify(imageService).publicUrl(7L);   // 插图 1
        order.verify(imageService).publicUrl(5L);   // 插图 2
    }
}
