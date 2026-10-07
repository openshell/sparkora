package com.sparkora.source.service;

import com.sparkora.service.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集正文配图转存单测(10-05-source-crawl-base,AC-B10):
 * 抽取链经 {@code saveExternalImage} 转存、source="source"/sourceRef=派生 news_id、
 * 相对链按 detail_base_url 传给 ImageService(域解析在 ImageService.resolveUrl)、单图失败不阻断。
 */
@ExtendWith(MockitoExtension.class)
class SourceImageServiceTest {

    @Mock ImageService imageService;

    SourceImageService service;

    @BeforeEach
    void setUp() {
        service = new SourceImageService(imageService);
    }

    @Test
    void 逐图转存_传source_sourceRef与detailBaseUrl() {
        service.transfer(List.of("/uploads/a.jpg", "https://cdn.x.com/b.png"),
                "https://www.miit.gov.cn", "1:10:ext-1", "某标题", "政策公示");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> base = ArgumentCaptor.forClass(String.class);
        verify(imageService, times(2)).saveExternalImage(isNull(), url.capture(), base.capture(),
                any(), eq("source"), any(), eq("system"), eq("1:10:ext-1"));
        assertEquals("/uploads/a.jpg", url.getAllValues().get(0));
        assertEquals("https://www.miit.gov.cn", base.getAllValues().get(0),
                "相对链基址须为栏目 detail_base_url(而非硬编码 byd.com)");
        assertEquals("https://www.miit.gov.cn", base.getAllValues().get(1));
    }

    @Test
    void 单图失败_继续其余不阻断() {
        when(imageService.saveExternalImage(isNull(), eq("/bad.jpg"), any(), any(), eq("source"),
                any(), eq("system"), eq("ref")))
                .thenThrow(new RuntimeException("下载失败"));

        int ok = service.transfer(List.of("/bad.jpg", "/good.jpg"),
                "https://www.gasgoo.com", "ref", "标题", "行业资讯");

        assertEquals(1, ok, "坏图跳过,好图成功");
        verify(imageService, times(2)).saveExternalImage(isNull(), any(), any(), any(), eq("source"),
                any(), eq("system"), eq("ref"));
    }

    @Test
    void 无图_不调用转存() {
        assertEquals(0, service.transfer(List.of(), "https://x.com", "ref", "t", "c"));
        assertEquals(0, service.transfer(null, "https://x.com", "ref", "t", "c"));
        verify(imageService, never()).saveExternalImage(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 标签含标题与分类() {
        assertEquals(List.of("某标题", "销量数据"), SourceImageService.buildTags("某标题", "销量数据"));
        assertEquals(List.of("某标题"), SourceImageService.buildTags("某标题", null));
        assertEquals(List.of(), SourceImageService.buildTags(null, " "));
    }
}
