package com.sparkora.qa.service;

import com.sparkora.car.service.CarRagService;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.dto.QaImageRef;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.service.ImageEmbeddingService;
import com.sparkora.service.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答配图解析服务单测（09-15 qa-auto-illustrate，子D；Mockito 不连库/不调 AI）。
 *
 * 覆盖：NEWS 引用 → 关联出图（链路 news_doc → news → cover_image_id → 图库）；
 * 无 cover_image_id / 图库无 url / docId 为空 → 跳过；非图片意图**不调** searchImages；
 * 图片意图调 searchImages；两路合并按 imageId 去重（新闻图优先）；上限截断；
 * 任一依赖异常 → 返回空列表**不抛出**（答案优先）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QaImageRefServiceTest {

    @Mock NewsDocMapper newsDocMapper;
    @Mock NewsMapper newsMapper;
    @Mock ImageService imageService;
    @Mock ImageEmbeddingService embeddingService;

    AiProperties aiProps;
    QaImageRefService service;

    @BeforeEach
    void setUp() {
        aiProps = new AiProperties();
        aiProps.setImageMinScore(0.3);
        service = new QaImageRefService(newsDocMapper, newsMapper, imageService, embeddingService, aiProps);
    }

    // ==================== 测试数据构造 ====================

    private static CarRagService.Citation newsCite(Long docId) {
        return new CarRagService.Citation("NEWS", "比亚迪发布新车型", "NEWS_BODY", 0.9, "块文本", docId);
    }

    private static CarRagService.Citation carCite() {
        return new CarRagService.Citation("CAR", "海狮08EV", "PARAM_GROUP", 0.9, "车型块");
    }

    private static NewsDocEntity doc(Long id, Long newsId) {
        NewsDocEntity d = new NewsDocEntity();
        d.setId(id);
        d.setNewsId(newsId);
        return d;
    }

    private static NewsEntity news(Long id, String newsId, Long coverImageId, String title) {
        NewsEntity n = new NewsEntity();
        n.setId(id);
        n.setNewsId(newsId);
        n.setCoverImageId(coverImageId);
        n.setTitle(title);
        return n;
    }

    private static ImageAssetEntity image(Long id, String url, String thumbUrl, String source) {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(id);
        img.setUrl(url);
        img.setThumbUrl(thumbUrl == null ? url : thumbUrl);
        img.setSource(source);
        return img;
    }

    private static ImageSearchHit hit(Long imageId, String url, String sourceText) {
        return new ImageSearchHit(imageId, 0.62, sourceText, "news.jpg", "byd-news", null, url, url + ".thumb", List.of());
    }

    // ==================== 新闻关联图（便宜路径） ====================

    @Test
    void 新闻引用_关联出封面图_带标题与newsId() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "/page/byd-cn/news-2026/detail588", 84L, "比亚迪发布新车型")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/x.jpg", "http://pic/x.thumb.jpg", "byd-news")));

        List<QaImageRef> refs = service.byNewsCitations(List.of(newsCite(1688L)), 3);

        assertEquals(1, refs.size());
        QaImageRef r = refs.get(0);
        assertEquals(84L, r.imageId());
        assertEquals("http://pic/x.jpg", r.url());
        assertEquals("http://pic/x.thumb.jpg", r.thumbUrl());
        assertEquals("比亚迪发布新车型", r.title());
        assertEquals("/page/byd-cn/news-2026/detail588", r.newsId());
        assertEquals("byd-news", r.source());
    }

    @Test
    void 无NEWS引用_不发任何查询_返回空() {
        assertTrue(service.byNewsCitations(List.of(carCite()), 3).isEmpty());
        assertTrue(service.byNewsCitations(List.of(), 3).isEmpty());
        assertTrue(service.byNewsCitations(null, 3).isEmpty());
        verify(newsDocMapper, never()).selectBatchIds(any());
    }

    @Test
    void docId为空的NEWS引用_跳过() {
        assertTrue(service.byNewsCitations(List.of(newsCite(null)), 3).isEmpty());
        verify(newsDocMapper, never()).selectBatchIds(any());
    }

    @Test
    void 新闻无coverImageId_跳过该条不报错() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", null, "无封面新闻")));

        assertTrue(service.byNewsCitations(List.of(newsCite(1688L)), 3).isEmpty());
        verify(imageService, never()).loadDerived(anyList());
    }

    @Test
    void 图库记录已删或无url_跳过该条() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻")));
        ImageAssetEntity noUrl = image(84L, null, null, "byd-news");
        when(imageService.loadDerived(anyList())).thenReturn(List.of(noUrl));

        assertTrue(service.byNewsCitations(List.of(newsCite(1688L)), 3).isEmpty(), "无 url 的图不得回传");
    }

    @Test
    void 图库查无该id_跳过() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of());   // 图已删

        assertTrue(service.byNewsCitations(List.of(newsCite(1688L)), 3).isEmpty());
    }

    @Test
    void 多引用_保citations相关度顺序_按上限截断() {
        List<CarRagService.Citation> cites = List.of(newsCite(1L), newsCite(2L), newsCite(3L), newsCite(4L));
        List<NewsDocEntity> docs = new ArrayList<>();
        for (long i = 1; i <= 4; i++) docs.add(doc(i, i * 10));
        List<NewsEntity> newsList = new ArrayList<>();
        for (long i = 1; i <= 4; i++) newsList.add(news(i * 10, "detail" + i, 100L + i, "新闻" + i));
        when(newsDocMapper.selectBatchIds(any())).thenReturn(docs);
        when(newsMapper.selectBatchIds(any())).thenReturn(newsList);
        when(imageService.loadDerived(anyList())).thenReturn(List.of(
                image(101L, "http://pic/1.jpg", null, "byd-news"),
                image(102L, "http://pic/2.jpg", null, "byd-news")));

        List<QaImageRef> refs = service.byNewsCitations(cites, 2);

        assertEquals(2, refs.size(), "上限截断");
        assertEquals(101L, refs.get(0).imageId());
        assertEquals(102L, refs.get(1).imageId());
    }

    @Test
    void 多个NEWS引用命中同一新闻_按imageId去重() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1L, 48L), doc(2L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "同一新闻")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/x.jpg", null, "byd-news")));

        List<QaImageRef> refs = service.byNewsCitations(List.of(newsCite(1L), newsCite(2L)), 3);

        assertEquals(1, refs.size(), "同一封面只出现一次");
    }

    // ==================== 意图判定与语义路径 ====================

    @Test
    void 非图片意图_不触发语义检索_无embedding浪费() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/x.jpg", null, "byd-news")));

        List<QaImageRef> refs = service.forAnswer(List.of(newsCite(1688L)), "比亚迪最近和谁合作建闪充生态？", 3);

        assertEquals(1, refs.size(), "新闻关联图路径始终执行");
        verify(embeddingService, never()).searchImages(anyString(), anyInt(), anyDouble(), any());
    }

    @Test
    void 图片意图_触发语义检索_与新闻图合并() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻标题")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/news.jpg", null, "byd-news")));
        when(embeddingService.searchImages(anyString(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(hit(90L, "http://pic/poster.jpg", "比亚迪销量创新高 主题/销量")));

        List<QaImageRef> refs = service.forAnswer(List.of(newsCite(1688L)), "给我看比亚迪销量海报", 3);

        assertEquals(2, refs.size());
        assertEquals(84L, refs.get(0).imageId(), "新闻关联图优先");
        assertEquals(90L, refs.get(1).imageId());
        assertEquals("比亚迪销量创新高 主题/销量", refs.get(1).title());
        assertNull(refs.get(1).newsId(), "语义图无 newsId");
        verify(embeddingService).searchImages(anyString(), eq(3), eq(0.3), isNull());
    }

    @Test
    void 两路命中同一imageId_合并去重_新闻图优先() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻标题")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/news.jpg", null, "byd-news")));
        // 语义路径也命中同一张图（但 url/标题来自语义命中）
        when(embeddingService.searchImages(anyString(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(hit(84L, "http://pic/semantic.jpg", "语义原文")));

        List<QaImageRef> refs = service.forAnswer(List.of(newsCite(1688L)), "给我看图", 3);

        assertEquals(1, refs.size(), "同 imageId 两路命中 → 1 条");
        assertEquals("新闻标题", refs.get(0).title(), "新闻关联图优先（与答案引用强相关）");
        assertEquals("http://pic/news.jpg", refs.get(0).url());
    }

    @Test
    void 新闻图已占满上限_不再调语义检索() {
        List<CarRagService.Citation> cites = List.of(newsCite(1L), newsCite(2L), newsCite(3L));
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1L, 10L), doc(2L, 20L), doc(3L, 30L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(
                news(10L, "d1", 101L, "n1"), news(20L, "d2", 102L, "n2"), news(30L, "d3", 103L, "n3")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(
                image(101L, "http://pic/1.jpg", null, "byd-news"),
                image(102L, "http://pic/2.jpg", null, "byd-news"),
                image(103L, "http://pic/3.jpg", null, "byd-news")));

        List<QaImageRef> refs = service.forAnswer(cites, "给我看图", 3);

        assertEquals(3, refs.size());
        verify(embeddingService, never()).searchImages(anyString(), anyInt(), anyDouble(), any());
    }

    // ==================== 降级：绝不阻断 ====================

    @Test
    void 新闻关联依赖抛异常_返回空列表不抛出() {
        when(newsDocMapper.selectBatchIds(any())).thenThrow(new RuntimeException("库炸了"));

        assertTrue(service.byNewsCitations(List.of(newsCite(1688L)), 3).isEmpty());
        assertTrue(service.forAnswer(List.of(newsCite(1688L)), "问题", 3).isEmpty());
    }

    @Test
    void 语义检索抛异常_返回空列表不抛出() {
        when(embeddingService.searchImages(anyString(), anyInt(), anyDouble(), any()))
                .thenThrow(new RuntimeException("embedding down"));

        assertTrue(service.bySemanticQuery("给我看海报", 3).isEmpty());
        assertTrue(service.forAnswer(List.of(), "给我看海报", 3).isEmpty());
    }

    @Test
    void limit非正数_不出图() {
        assertTrue(service.forAnswer(List.of(newsCite(1688L)), "给我看图", 0).isEmpty());
        assertTrue(service.byNewsCitations(List.of(newsCite(1688L)), 0).isEmpty());
        assertTrue(service.bySemanticQuery("给我看图", 0).isEmpty());
    }

    @Test
    void 语义命中无url_跳过() {
        when(embeddingService.searchImages(anyString(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(hit(90L, null, "无 url 命中")));

        assertTrue(service.bySemanticQuery("给我看海报", 3).isEmpty());
    }

    // ==================== 硬约束：只读（不产生任何写入用户内容的副作用） ====================

    /**
     * 配图是**只读附加展示**（用户 09-17 决策）：无批准流程、不写任何用户内容。
     * 断言解析过程不触碰任何图库写路径（setCover/modifyBodyImage/persistOrReuse/delete/upload）。
     */
    @Test
    void 只读契约_不触发任何图库写路径() {
        when(newsDocMapper.selectBatchIds(any())).thenReturn(List.of(doc(1688L, 48L)));
        when(newsMapper.selectBatchIds(any())).thenReturn(List.of(news(48L, "detail588", 84L, "新闻")));
        when(imageService.loadDerived(anyList())).thenReturn(List.of(image(84L, "http://pic/x.jpg", null, "byd-news")));
        when(embeddingService.searchImages(anyString(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(hit(90L, "http://pic/p.jpg", "海报")));

        service.forAnswer(List.of(newsCite(1688L)), "给我看海报", 3);

        verify(imageService, never()).persistOrReuse(any(), anyString(), any());
        verify(imageService, never()).delete(any());
        verify(imageService, never()).setCover(any(), any());
        verify(imageService, never()).modifyBodyImage(any(), any(), anyString());
        verify(imageService, never()).upload(any(), any(), any(), anyString());
        verify(embeddingService, never()).embedQuietly(any());
    }
}
