package com.sparkora.source.service;

import com.sparkora.ai.EmbeddingBatchRunner;
import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.domain.entity.NewsDocEntity;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用信源切块/向量化单测（10-05-source-domain-retrieval E，AC-E1/E8/E9）。
 *
 * 覆盖：首行锚点「信源：…」、结构化分类保留换行、store metadata sourceType/category/publishDate、
 * refId=news_doc.id、embedding 失败不阻断（失败计数）、BYD 行被跳过。
 */
class SourceDocServiceTest {

    /** 固定返回合法向量的 embedding 假件。 */
    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() { super(props()); }
        private static AiProperties props() {
            AiProperties p = new AiProperties();
            p.setEmbeddingModel("test-embed");
            return p;
        }
        @Override public String embed(String text) { return "[0.1,0.2]"; }
    }

    private static void setSelf(SourceDocService target, SourceDocService proxy) {
        try {
            Field f = SourceDocService.class.getDeclaredField("self");
            f.setAccessible(true);
            f.set(target, proxy);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setStore(SourceDocService target, VectorStoreService store) {
        try {
            Field f = SourceDocService.class.getDeclaredField("vectorStoreService");
            f.setAccessible(true);
            f.set(target, store);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SourceDocService newService(NewsMapper newsMapper, NewsDocMapper docMapper,
                                               SourceMapper sourceMapper, SourceChannelMapper channelMapper,
                                               EmbeddingClient client) {
        return new SourceDocService(newsMapper, docMapper, sourceMapper, channelMapper, client,
                new EmbeddingBatchRunner(client));
    }

    // ==================== 纯函数：切块 ====================

    @Test
    void 首行锚点_信源标题与日期() {
        List<String> chunks = SourceDocService.chunkContent("工信部公示", LocalDateTime.of(2026, 10, 8, 9, 0),
                "正文第一段。\n\n第二段。", "政策公示");
        assertEquals(2, chunks.size());
        for (String c : chunks) {
            assertTrue(c.startsWith("信源：工信部公示（2026-10-08）\n"), () -> "块首行不符: " + c);
        }
    }

    @Test
    void 结构化分类_保留换行_数值行不丢() {
        String table = "车型 | 销量 | 同比\n海狮08 | 12000 | +15%\n大唐 | 9000 | -3%";
        List<String> chunks = SourceDocService.chunkContent("乘联会销量", LocalDateTime.of(2026, 10, 8, 0, 0),
                table, "销量数据");
        assertEquals(1, chunks.size());
        String body = chunks.get(0).substring(chunks.get(0).indexOf('\n') + 1);
        assertTrue(body.contains("\n"), "结构化内容换行必须保留: " + body);
        assertTrue(body.contains("海狮08 | 12000 | +15%"), body);
    }

    @Test
    void 非结构化分类_走默认压平() {
        String text = "第一行\n第二行";
        List<String> chunks = SourceDocService.chunkContent("行业资讯", LocalDateTime.of(2026, 10, 8, 0, 0),
                text, "行业资讯");
        String body = chunks.get(0).substring(chunks.get(0).indexOf('\n') + 1);
        assertTrue(!body.contains("\n"), "非结构化分类保持旧压平行为: " + body);
    }

    @Test
    void 块类型_空正文仅标题为NEWS_TITLE() {
        List<String> chunks = SourceDocService.chunkContent("图片型", LocalDateTime.of(2026, 10, 8, 0, 0),
                "  \n ", "行业资讯");
        assertEquals(List.of("信源：图片型（2026-10-08）"), chunks);
        assertEquals("NEWS_TITLE", SourceDocService.chunkTypeOf(chunks));
    }

    @Test
    void 空正文且无标题_返回空列表() {
        assertTrue(SourceDocService.chunkContent(null, null, "", "行业资讯").isEmpty());
        assertTrue(SourceDocService.chunkContent("  ", null, null, "政策公示").isEmpty());
    }

    // ==================== 入库：refId=news_doc.id + metadata ====================

    @Test
    void rebuildForNews_写store_refId为newsDocId_metadata带sourceType与category() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        SourceChannelMapper channelMapper = mock(SourceChannelMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        SourceDocService service = newService(newsMapper, docMapper, sourceMapper, channelMapper, client);
        setStore(service, store);

        NewsEntity n = new NewsEntity();
        n.setId(500L);
        n.setSourceId(1L);
        n.setChannelId(10L);
        n.setTitle("工信部公示");
        n.setCategory("政策公示");
        n.setPublishDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        n.setContent("公示正文。");
        when(newsMapper.selectById(500L)).thenReturn(n);

        SourceEntity src = new SourceEntity();
        src.setId(1L);
        src.setName("工信部");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        SourceChannelEntity ch = new SourceChannelEntity();
        ch.setId(10L);
        ch.setSourceId(1L);
        ch.setName("公示");
        ch.setCategory("政策公示");
        when(channelMapper.selectById(10L)).thenReturn(ch);

        // 模拟 MyBatis-Plus 回填自增 id → refId 必须是这个 id
        doAnswer(inv -> { ((NewsDocEntity) inv.getArgument(0)).setId(9001L); return 1; })
                .when(docMapper).insert(any(NewsDocEntity.class));

        service.rebuildForNews(500L);

        // 断言 store upsert 用 domain=NEWS + refId=news_doc.id,且 metadata 带 sourceType/category
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> meta =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(store).upsert(eq("NEWS"), eq(9001L), eq(null), eq("NEWS_BODY"), eq("工信部公示"),
                eq(true), eq("test-embed"), anyString(), eq("[0.1,0.2]"), meta.capture());
        assertEquals("user-source", meta.getValue().get("sourceType"));
        assertEquals("政策公示", meta.getValue().get("category"));
        assertEquals("2026-10-01", meta.getValue().get("publishDate"));
    }

    @Test
    void rebuildForNews_盖世栏目_可区分官宣与排行来源类型() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        SourceChannelMapper channelMapper = mock(SourceChannelMapper.class);
        VectorStoreService store = mock(VectorStoreService.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        SourceDocService service = newService(newsMapper, docMapper, sourceMapper, channelMapper, client);
        setStore(service, store);

        NewsEntity n = new NewsEntity();
        n.setId(501L);
        n.setSourceId(2L);
        n.setChannelId(20L);
        n.setTitle("盖世销量排行");
        n.setCategory("销量数据");
        n.setContent("排行正文。");
        when(newsMapper.selectById(501L)).thenReturn(n);
        SourceEntity src = new SourceEntity();
        src.setId(2L);
        src.setName("盖世汽车");
        when(sourceMapper.selectById(2L)).thenReturn(src);
        SourceChannelEntity ch = new SourceChannelEntity();
        ch.setId(20L);
        ch.setName("销量排行");
        when(channelMapper.selectById(20L)).thenReturn(ch);
        doAnswer(inv -> { ((NewsDocEntity) inv.getArgument(0)).setId(9002L); return 1; })
                .when(docMapper).insert(any(NewsDocEntity.class));

        service.rebuildForNews(501L);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> meta =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(store).upsert(eq("NEWS"), eq(9002L), eq(null), eq("NEWS_BODY"), anyString(),
                eq(true), anyString(), anyString(), anyString(), meta.capture());
        assertEquals("gasgoo-ranking", meta.getValue().get("sourceType"), "盖世排行须与官宣区分");
    }

    @Test
    void rebuildForNews_BYD行被跳过_不调embedding不写store() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        EmbeddingClient client = mock(EmbeddingClient.class);
        SourceDocService service = newService(newsMapper, docMapper, mock(SourceMapper.class),
                mock(SourceChannelMapper.class), client);

        NewsEntity byd = new NewsEntity();
        byd.setId(9L);
        byd.setSourceId(null);   // BYD 存量行
        when(newsMapper.selectById(9L)).thenReturn(byd);

        service.rebuildForNews(9L);

        verify(client, never()).embed(anyString());
        verify(docMapper, never()).insert(any(NewsDocEntity.class));
    }

    @Test
    void rebuild_经自注入代理走独立事务持久化() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        SourceChannelMapper channelMapper = mock(SourceChannelMapper.class);
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        SourceDocService service = newService(newsMapper, docMapper, sourceMapper, channelMapper, client);
        SourceDocService spySelf = spy(service);
        setSelf(service, spySelf);

        NewsEntity n = new NewsEntity();
        n.setId(500L);
        n.setSourceId(1L);
        n.setTitle("标题");
        n.setContent("正文。");
        when(newsMapper.selectById(500L)).thenReturn(n);
        when(sourceMapper.selectById(1L)).thenReturn(null);
        when(channelMapper.selectById(any())).thenReturn(null);

        service.rebuildForNews(500L);

        verify(spySelf).persistSourceDoc(any(NewsDocEntity.class), eq("[0.1,0.2]"));
    }

    // ==================== 降级：embedding 失败不阻断 ====================

    @Test
    void embedding失败_不抛出_失败计数() {
        NewsMapper newsMapper = mock(NewsMapper.class);
        NewsDocMapper docMapper = mock(NewsDocMapper.class);
        SourceMapper sourceMapper = mock(SourceMapper.class);
        SourceChannelMapper channelMapper = mock(SourceChannelMapper.class);
        EmbeddingClient failing = mock(EmbeddingClient.class);
        when(failing.modelName()).thenReturn("test-embed");
        when(failing.embedForIndex(anyString())).thenThrow(new RuntimeException("embedding 挂了"));
        SourceDocService service = newService(newsMapper, docMapper, sourceMapper, channelMapper, failing);

        NewsEntity n = new NewsEntity();
        n.setId(500L);
        n.setSourceId(1L);
        n.setTitle("标题");
        n.setContent("正文。");
        when(newsMapper.selectById(500L)).thenReturn(n);
        when(sourceMapper.selectById(1L)).thenReturn(null);
        when(channelMapper.selectById(any())).thenReturn(null);

        // 不抛出异常
        var stats = service.rebuildForNews(500L);
        assertNotNull(stats);
        assertEquals(0, stats.success(), "embedding 失败应计入失败而非抛出");
        assertTrue(stats.failed() >= 1);
    }
}
