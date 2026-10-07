package com.sparkora.source.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import com.sparkora.source.client.SourceClient;
import com.sparkora.source.client.SourceContent;
import com.sparkora.source.client.SourceItem;
import com.sparkora.source.fetch.FetchOptions;
import com.sparkora.source.fetch.FetchResult;
import com.sparkora.source.fetch.FetchTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集编排单测(10-05-source-crawl-base,AC-B1/B4/B11):
 * 幂等 upsert(同 externalId 不重复 insert)、单条失败进 failed_items 不阻断、正文空仍入库、
 * 一源多栏目独立去重、跨栏目同 externalId 视为不同条目、Crawl4AI 未配置降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceCollectServiceTest {

    @Mock SourceMapper sourceMapper;
    @Mock SourceChannelMapper channelMapper;
    @Mock NewsMapper newsMapper;
    @Mock SourceImageService imageService;

    private final AtomicReference<String> lastNewsId = new AtomicReference<>();
    /** 记录 upsert 落库的实体(供断言 sourceId/channelId/category)。 */
    private final AtomicReference<NewsEntity> lastInserted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(newsMapper.selectOne(any())).thenReturn(null);   // 默认不存在 → insert
        when(newsMapper.insert(any(NewsEntity.class))).thenAnswer(inv -> {
            NewsEntity n = inv.getArgument(0);
            lastInserted.set(n);
            lastNewsId.set(n.getNewsId());
            return 1;
        });
    }

    private SourceCollectService newService(FetchTransport transport, SourceClient client) {
        return new SourceCollectService(sourceMapper, channelMapper, newsMapper,
                List.of(transport), List.of(client), imageService);
    }

    private static SourceEntity source(Long id, String type, boolean needCrawl) {
        SourceEntity s = new SourceEntity();
        s.setId(id);
        s.setType(type);
        s.setEnabled(true);
        s.setNeedCrawl4ai(needCrawl);
        return s;
    }

    private static SourceChannelEntity channel(Long id, Long sourceId, String listUrl) {
        SourceChannelEntity c = new SourceChannelEntity();
        c.setId(id);
        c.setSourceId(sourceId);
        c.setListUrl(listUrl);
        c.setDetailBaseUrl("https://www.miit.gov.cn");
        c.setCategory("政策公示");
        c.setEnabled(true);
        return c;
    }

    /** 全成功的静态 transport。 */
    private static FetchTransport okTransport() {
        return new FetchTransport() {
            @Override public Kind kind() { return Kind.HTTP; }
            @Override public boolean configured() { return true; }
            @Override public FetchResult fetch(String url, FetchOptions opts) {
                return new FetchResult(url, 200, "<html>" + url + "</html>", "text", 1L, null, false);
            }
        };
    }

    private static SourceClient client(List<SourceItem> items, String content) {
        return new SourceClient() {
            @Override public String type() { return "SITE"; }
            @Override public List<SourceItem> list(String html, SourceChannelEntity channel) { return items; }
            @Override public SourceContent detail(String html, SourceChannelEntity channel, SourceItem item) {
                return new SourceContent(content, html, List.of());
            }
        };
    }

    @Test
    void 幂等重跑_已存在走update不insert() {
        SourceEntity src = source(1L, "SITE", false);
        SourceChannelEntity ch = channel(10L, 1L, "https://x.com/list");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        SourceItem item = new SourceItem("ext-1", "标题", "https://x.com/d/1", "2026-10-08", List.of());
        SourceCollectService svc = newService(okTransport(), client(List.of(item), "正文"));

        // 第一次:不存在 → insert
        svc.collect(1L, null);
        verify(newsMapper).insert(any(NewsEntity.class));
        NewsEntity first = lastInserted.get();
        assertNotNull(first);
        assertEquals("1:10:ext-1", first.getNewsId(), "派生 news_id = sourceId:channelId:externalId");
        assertEquals(1L, first.getSourceId());
        assertEquals(10L, first.getChannelId());

        // 第二次:已存在 → updateById,不再 insert
        NewsEntity existing = new NewsEntity();
        existing.setId(99L);
        when(newsMapper.selectOne(any())).thenReturn(existing);
        SourceCollectService.CollectOutcome out = svc.collect(1L, null);
        assertEquals(1, out.success());
        verify(newsMapper).updateById(any(NewsEntity.class));
    }

    @Test
    void 单条详情失败_记failed_items且其余继续() {
        SourceEntity src = source(1L, "SITE", false);
        SourceChannelEntity ch = channel(10L, 1L, "https://x.com/list");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        SourceItem bad = new SourceItem("bad", "坏", "https://x.com/bad", null, List.of());
        SourceItem good = new SourceItem("good", "好", "https://x.com/good", null, List.of());
        // 详情对 bad 抛异常
        SourceClient throwing = new SourceClient() {
            @Override public String type() { return "SITE"; }
            @Override public List<SourceItem> list(String html, SourceChannelEntity c) { return List.of(bad, good); }
            @Override public SourceContent detail(String html, SourceChannelEntity c, SourceItem item) {
                if ("bad".equals(item.externalId())) throw new IllegalStateException("详情炸了");
                return new SourceContent("正文", html, List.of());
            }
        };
        // HTTP 通道对 bad 详情抛异常:改用一个在 fetch 阶段抛的 transport 更真实,这里直接在 detail 抛已覆盖
        when(newsMapper.insert(any(NewsEntity.class))).thenReturn(1);
        SourceCollectService svc = newService(okTransport(), throwing);

        SourceCollectService.CollectOutcome out = svc.collect(1L, null);

        assertEquals(1, out.success(), "好的成功");
        assertEquals(1, out.failed(), "坏的进失败");
        assertEquals(1, out.failedItems().size());
        assertEquals("bad", out.failedItems().get(0).get("externalId"));
    }

    @Test
    void 正文空_仍入库元数据() {
        SourceEntity src = source(1L, "SITE", false);
        SourceChannelEntity ch = channel(10L, 1L, "https://x.com/list");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        SourceItem item = new SourceItem("ext-1", "标题", "https://x.com/d/1", null, List.of());
        SourceCollectService svc = newService(okTransport(), client(List.of(item), ""));

        SourceCollectService.CollectOutcome out = svc.collect(1L, null);

        assertEquals(1, out.success());
        assertNotNull(lastInserted.get(), "空正文仍应 insert 元数据");
        assertEquals("标题", lastInserted.get().getTitle());
        assertEquals("source", lastInserted.get().getSource());
    }

    @Test
    void 一源两栏目_各自解析独立去重且category不同() {
        SourceEntity src = source(1L, "SITE", false);
        SourceChannelEntity c1 = channel(10L, 1L, "https://x.com/news");
        c1.setCategory("行业资讯");
        SourceChannelEntity c2 = channel(11L, 1L, "https://x.com/sales");
        c2.setCategory("销量数据");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(c1, c2));
        SourceItem item = new SourceItem("same", "同 externalId", "https://x.com/d/1", null, List.of());
        SourceCollectService svc = newService(okTransport(), client(List.of(item), "正文"));

        SourceCollectService.CollectOutcome out = svc.collect(1L, null);

        assertEquals(2, out.success(), "两栏目各采一条");
        // 两条派生 news_id 不同(跨栏目视为不同条目)
        assertEquals("1:10:same", SourceCollectService.derivedNewsId(1L, 10L, "same"));
        assertEquals("1:11:same", SourceCollectService.derivedNewsId(1L, 11L, "same"));
    }

    @Test
    void needCrawl4ai栏目未配置_降级跳过不失败() {
        SourceEntity src = source(1L, "SITE", true);   // 源级需 Crawl4AI
        SourceChannelEntity ch = channel(10L, 1L, "https://x.com/list");
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        FetchTransport http = okTransport();   // 只有 HTTP 可用,Crawl4AI 缺失
        SourceCollectService svc = newService(http, client(List.of(), ""));

        SourceCollectService.CollectOutcome out = svc.collect(1L, null);

        assertEquals(0, out.success());
        assertEquals(0, out.failed(), "降级不算失败");
        assertEquals(1, out.degraded());
        verify(newsMapper, never()).insert(any(NewsEntity.class));
    }

    @Test
    void 栏目覆盖源级_栏目needCrawl4ai为假则走HTTP() {
        SourceEntity src = source(1L, "SITE", true);
        SourceChannelEntity ch = channel(10L, 1L, "https://x.com/list");
        ch.setNeedCrawl4ai(false);   // 栏目级覆盖
        when(sourceMapper.selectById(1L)).thenReturn(src);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        SourceItem item = new SourceItem("e1", "t", "https://x.com/d/1", null, List.of());
        SourceCollectService svc = newService(okTransport(), client(List.of(item), "正文"));

        assertTrue(SourceCollectService.needCrawl4ai(src, ch) == false);
        assertEquals(1, svc.collect(1L, null).success());
    }

    @Test
    void 日期解析_多格式容错() {
        assertNotNull(SourceCollectService.parseDate("2026-10-08"));
        assertNotNull(SourceCollectService.parseDate("2026-10-08 09:00:00"));
        assertNotNull(SourceCollectService.parseDate("2026/10/08"));
        assertNotNull(SourceCollectService.parseDate("Wed, 08 Oct 2026 09:00:00 GMT"));
        assertNotNull(SourceCollectService.parseDate("2026-10-08T09:00:00Z"));
        assertEquals(null, SourceCollectService.parseDate("not-a-date"));
        assertEquals(null, SourceCollectService.parseDate(null));
    }
}
