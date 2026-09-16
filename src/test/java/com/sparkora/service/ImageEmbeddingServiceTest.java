package com.sparkora.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.config.QiniuProperties;
import com.sparkora.domain.dto.ImageSearchHit;
import com.sparkora.domain.entity.ImageAssetEntity;
import com.sparkora.mapper.ImageAssetMapper;
import com.sparkora.mapper.ImageEmbeddingMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 图片语义向量服务单测（09-15 img-semantic-search，Mockito 不连库/不调 AI）。
 * 覆盖：query 空校验、标签交集空早返回（不调 embedding）、topK 收敛、minScore 默认与门槛透传、
 * 命中回填（url/thumbUrl/tags/sourceRef）、图已删跳过、embedQuietly 吞异常、rebuild 计数。
 */
@ExtendWith(MockitoExtension.class)
class ImageEmbeddingServiceTest {

    @Mock ImageEmbeddingMapper embMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock EmbeddingClient embeddingClient;
    @Mock ImageTagService tagService;
    @Mock ImageStorage imageStorage;
    @Mock ObjectProvider<QiniuProperties> qiniuProps;
    @Mock ObjectProvider<NewsMapper> newsMapper;

    AiProperties aiProps;
    ImageEmbeddingService service;

    @BeforeEach
    void setUp() {
        aiProps = new AiProperties();
        aiProps.setImageMinScore(0.3);
        service = new ImageEmbeddingService(embMapper, imageMapper, embeddingClient, tagService,
                imageStorage, aiProps, qiniuProps, newsMapper);
    }

    // ==================== topK 收敛（纯函数） ====================

    @Test
    void topK收敛规则() {
        assertEquals(10, ImageEmbeddingService.normalizeTopK(null));
        assertEquals(10, ImageEmbeddingService.normalizeTopK(0));
        assertEquals(10, ImageEmbeddingService.normalizeTopK(-5));
        assertEquals(3, ImageEmbeddingService.normalizeTopK(3));
        assertEquals(50, ImageEmbeddingService.normalizeTopK(50));
        assertEquals(50, ImageEmbeddingService.normalizeTopK(999));
    }

    // ==================== 入参校验 + 早返回 ====================

    @Test
    void query空抛IllegalArgumentException() {
        assertEquals("检索内容不能为空",
                assertThrows(IllegalArgumentException.class, () -> service.searchImages(null, 10, null, null)).getMessage());
        assertEquals("检索内容不能为空",
                assertThrows(IllegalArgumentException.class, () -> service.searchImages("   ", 10, null, null)).getMessage());
    }

    @Test
    void 标签交集为空_早返回空列表且不调embedding() {
        when(tagService.imageIdsByTag("主题/销量")).thenReturn(List.of(1L, 2L));
        when(tagService.imageIdsByTag("年份/2023")).thenReturn(List.of(9L));

        List<ImageSearchHit> hits = service.searchImages("海报", 10, null, List.of("主题/销量", "年份/2023"));

        assertTrue(hits.isEmpty());
        verify(embeddingClient, never()).embed(anyString());
        verify(embMapper, never()).searchTopK(anyString(), any(), anyDouble(), anyInt());
    }

    @Test
    void 未指定标签_不查标签且不传白名单() {
        when(embeddingClient.embed("销量海报")).thenReturn("[0.1,0.2]");
        when(embMapper.searchTopK(anyString(), isNull(), anyDouble(), anyInt())).thenReturn(List.of());

        assertTrue(service.searchImages("销量海报", 10, null, null).isEmpty());

        verify(tagService, never()).imageIdsByTag(anyString());
        verify(embMapper).searchTopK(eq("[0.1,0.2]"), isNull(), eq(0.3), eq(10));
    }

    // ==================== 门槛与 limit 透传 ====================

    @Test
    void minScore为null用配置默认_显式值优先() {
        when(embeddingClient.embed(anyString())).thenReturn("[0]");
        when(embMapper.searchTopK(anyString(), isNull(), anyDouble(), anyInt())).thenReturn(List.of());

        service.searchImages("海报", 5, null, null);
        verify(embMapper).searchTopK(eq("[0]"), isNull(), eq(0.3), eq(5));   // 配置默认 0.3

        service.searchImages("海报", 999, 0.99, null);
        verify(embMapper).searchTopK(eq("[0]"), isNull(), eq(0.99), eq(50)); // 显式门槛 + topK 收敛 50
    }

    @Test
    void 标签预过滤_白名单传入SQL且命中集截断() {
        when(tagService.imageIdsByTag("主题/销量")).thenReturn(List.of(1L, 2L, 3L));
        when(embeddingClient.embed(anyString())).thenReturn("[0]");
        when(embMapper.searchTopK(anyString(), anyList(), anyDouble(), anyInt())).thenReturn(List.of());

        service.searchImages("海报", 10, null, List.of("主题/销量"));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(embMapper).searchTopK(eq("[0]"), captor.capture(), eq(0.3), eq(10));
        assertEquals(List.of(1L, 2L, 3L), captor.getValue());
    }

    // ==================== 命中回填 ====================

    @Test
    void 命中回填主表字段与标签_url与thumbUrl派生() {
        when(tagService.imageIdsByTag("主题/销量")).thenReturn(List.of(7L));
        when(embeddingClient.embed("销量海报")).thenReturn("[0.1]");
        when(embMapper.searchTopK(anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn(List.of(Map.of("imageId", 7L, "sourceText", "比亚迪销量创新高 主题/销量", "score", 0.62)));

        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(7L);
        img.setFileName("news-detail632.jpg");
        img.setSource("byd-news");
        img.setSourceRef("/page/byd-cn/news-2026/detail632");
        img.setStorageKey("images/abc.jpg");
        when(imageMapper.selectBatchIds(List.of(7L))).thenReturn(List.of(img));
        when(imageStorage.publicUrl("images/abc.jpg")).thenReturn("http://pic.caiqz.cn/images/abc.jpg");
        when(qiniuProps.getIfAvailable()).thenReturn(null);   // 非七牛：thumbUrl 降级为原图 url
        // fillTags 是 void 副作用方法（生产实现回填 entity.tags）：mock 侧需 doAnswer 模拟
        org.mockito.Mockito.doAnswer(inv -> {
            List<ImageAssetEntity> list = inv.getArgument(0);
            for (ImageAssetEntity it : list) it.setTags(List.of("主题/销量"));
            return null;
        }).when(tagService).fillTags(anyList());

        List<ImageSearchHit> hits = service.searchImages("销量海报", 10, null, List.of("主题/销量"));

        assertEquals(1, hits.size());
        ImageSearchHit h = hits.get(0);
        assertEquals(7L, h.imageId());
        assertEquals(0.62, h.score(), 1e-9);
        assertEquals("比亚迪销量创新高 主题/销量", h.sourceText());
        assertEquals("news-detail632.jpg", h.fileName());
        assertEquals("byd-news", h.source());
        assertEquals("/page/byd-cn/news-2026/detail632", h.sourceRef());
        assertEquals("http://pic.caiqz.cn/images/abc.jpg", h.url());
        assertEquals("http://pic.caiqz.cn/images/abc.jpg", h.thumbUrl(), "非七牛实现 thumbUrl 降级为原图 url");
        assertEquals(List.of("主题/销量"), h.tags());
    }

    @Test
    void 向量残留但图已删_跳过该命中() {
        when(embeddingClient.embed(anyString())).thenReturn("[0.1]");
        when(embMapper.searchTopK(anyString(), isNull(), anyDouble(), anyInt()))
                .thenReturn(List.of(Map.of("imageId", 404L, "sourceText", "x", "score", 0.9)));
        when(imageMapper.selectBatchIds(List.of(404L))).thenReturn(List.of());

        assertTrue(service.searchImages("海报", 10, null, null).isEmpty());
    }

    // ==================== 增量 / 重建 ====================

    @Test
    void embedQuietly_异常吞掉不抛出() {
        when(imageMapper.selectById(5L)).thenReturn(null);
        service.embedQuietly(5L);   // 不抛
        ImageAssetEntity exists = new ImageAssetEntity();
        exists.setId(6L);
        exists.setSource("upload");
        when(imageMapper.selectById(6L)).thenReturn(exists);
        when(tagService.tagNamesOf(6L)).thenThrow(new RuntimeException("库炸了"));
        service.embedQuietly(6L);   // 不抛
        service.embedQuietly(null); // 不抛
        verify(embeddingClient, never()).embed(anyString());
    }

    @Test
    void embedOne_先删后插_幂等顺序() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(9L);
        img.setSource("upload");
        img.setFileName("出海签约.jpg");
        when(tagService.tagNamesOf(9L)).thenReturn(List.of("主题/合作签约"));
        when(embeddingClient.embed("出海签约 主题/合作签约")).thenReturn("[0.5]");

        service.embedOne(img);

        var inOrder = org.mockito.Mockito.inOrder(embMapper);
        inOrder.verify(embMapper).deleteByImageId(9L);
        inOrder.verify(embMapper).insert(9L, "[0.5]", "出海签约 主题/合作签约");
    }

    @Test
    void persistVector_先删后插_可独立事务边界调用() {
        service.persistVector(9L, "[0.5]", "文本");

        var inOrder = org.mockito.Mockito.inOrder(embMapper);
        inOrder.verify(embMapper).deleteByImageId(9L);
        inOrder.verify(embMapper).insert(9L, "[0.5]", "文本");
    }

    /** self 未注入（直接 new 的单测场景）时退化为直写，不得 NPE。 */
    @Test
    void embedOne_无代理时退化为直写不NPE() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(9L);
        img.setSource("upload");
        img.setFileName("a.png");
        when(tagService.tagNamesOf(9L)).thenReturn(List.of());
        when(embeddingClient.embed("a")).thenReturn("[0.5]");

        service.embedOne(img);   // self == null（未反射注入）

        verify(embMapper).deleteByImageId(9L);
        verify(embMapper).insert(9L, "[0.5]", "a");
    }

    /**
     * 事务隔离回归：embedOne 必须经自注入代理（self）走独立事务写入（REQUIRES_NEW），
     * 而非在当前（可能属于调用方环境事务的）SqlSession 里直写——否则向量写失败会把
     * 调用方事务（新闻/车型同步）拖成 aborted，「嵌入失败不阻断入库」契约失效。
     */
    @Test
    void embedOne_经自注入代理走独立事务路径() {
        ImageEmbeddingService spySelf = org.mockito.Mockito.spy(service);
        setSelf(service, spySelf);

        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(9L);
        img.setSource("upload");
        img.setFileName("a.png");
        when(tagService.tagNamesOf(9L)).thenReturn(List.of());
        when(embeddingClient.embed("a")).thenReturn("[0.5]");

        service.embedOne(img);

        // 经代理（独立事务边界）——直写由代理内的真实实现完成
        verify(spySelf).persistVector(9L, "[0.5]", "a");
        verify(embMapper).deleteByImageId(9L);
        verify(embMapper).insert(9L, "[0.5]", "a");
    }

    /** 反射注入 self（生产由 Spring @Autowired @Lazy 装配；单测无容器）。 */
    private static void setSelf(ImageEmbeddingService target, ImageEmbeddingService proxy) {
        try {
            java.lang.reflect.Field f = ImageEmbeddingService.class.getDeclaredField("self");
            f.setAccessible(true);
            f.set(target, proxy);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rebuildAll_单图失败跳过并计数() {
        ImageAssetEntity ok = new ImageAssetEntity();
        ok.setId(1L);
        ok.setSource("upload");
        ImageAssetEntity bad = new ImageAssetEntity();
        bad.setId(2L);
        bad.setSource("upload");
        when(imageMapper.selectList(any())).thenReturn(List.of(ok, bad));
        when(tagService.tagNamesOf(1L)).thenReturn(List.of());
        when(tagService.tagNamesOf(2L)).thenReturn(List.of());
        when(embeddingClient.embed("(图片 1)")).thenReturn("[0]");
        when(embeddingClient.embed("(图片 2)")).thenThrow(new RuntimeException("超时"));

        ImageEmbeddingService.EmbedStats st = service.rebuildAll();

        assertEquals(2, st.total());
        assertEquals(1, st.success());
        assertEquals(1, st.failed());
    }

    @Test
    void rebuildMissing_无缺失时零副作用() {
        when(embMapper.findImageIdsWithoutEmbedding()).thenReturn(List.of());

        ImageEmbeddingService.EmbedStats st = service.rebuildMissing();

        assertEquals(0, st.total());
        assertEquals(0, st.success());
        assertEquals(0, st.failed());
        verify(imageMapper, never()).selectBatchIds(anyList());
    }

    @Test
    void deleteByImageId_物理清向量() {
        when(embMapper.deleteByImageId(3L)).thenReturn(1);
        service.deleteByImageId(3L);
        service.deleteByImageId(null);   // 无副作用
        verify(embMapper).deleteByImageId(3L);
    }

    @Test
    void 新闻图嵌入文本含来源新闻标题() {
        ImageAssetEntity img = new ImageAssetEntity();
        img.setId(11L);
        img.setSource("byd-news");
        img.setFileName("news.jpg");
        img.setSourceRef("/page/byd-cn/news-2026/detail632");
        when(tagService.tagNamesOf(11L)).thenReturn(List.of("新闻", "主题/销量", "年份/2026"));
        NewsMapper nm = org.mockito.Mockito.mock(NewsMapper.class);
        com.sparkora.domain.entity.NewsEntity news = new com.sparkora.domain.entity.NewsEntity();
        news.setTitle("比亚迪7月销售41.9万辆");
        when(nm.selectOne(any())).thenReturn(news);
        when(newsMapper.getIfAvailable()).thenReturn(nm);
        when(embeddingClient.embed(anyString())).thenReturn("[0]");

        service.embedOne(img);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient).embed(captor.capture());
        assertTrue(captor.getValue().startsWith("比亚迪7月销售41.9万辆"), captor.getValue());
        assertTrue(captor.getValue().contains("主题/销量"));
        assertFalse(captor.getValue().contains("news.jpg"), "byd-news 优先用标题，不回退文件名");
    }
}
