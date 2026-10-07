package com.sparkora.news.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sparkora.config.NewsProperties;
import com.sparkora.domain.entity.NewsEntity;
import com.sparkora.domain.entity.NewsSyncJobEntity;
import com.sparkora.mapper.NewsDocMapper;
import com.sparkora.mapper.NewsMapper;
import com.sparkora.news.client.BydNewsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新闻来源隔离单测(10-05-source-crawl-base,AC-B6):/api/news 只返回 BYD 来源,通用信源内容不泄漏。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewsServiceSourceIsolationTest {

    @Mock NewsProperties props;
    @Mock BydNewsClient client;
    @Mock NewsMapper newsMapper;
    @Mock NewsDocMapper docMapper;
    @Mock NewsDocService docService;
    @Mock com.sparkora.service.ImageService imageService;

    NewsService service;

    @BeforeEach
    void setUp() {
        service = new NewsService(props, client, newsMapper, docMapper, docService,
                new com.fasterxml.jackson.databind.ObjectMapper(), imageService);
    }

    @Test
    void 列表查询_带BYD来源过滤_BYD或sourceId为空() {
        when(newsMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(inv -> {
            Page<NewsEntity> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        service.list(1, 12, null);

        org.mockito.ArgumentCaptor<Wrapper<NewsEntity>> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(newsMapper).selectPage(any(), captor.capture());
        AbstractWrapper aw = (AbstractWrapper) captor.getValue();
        String sql = aw.getSqlSegment();
        assertTrue(sql.contains("source"), "应过滤 source: " + sql);
        assertTrue(sql.contains("source_id") && sql.contains("IS NULL"), "存量 BYD(source_id IS NULL)也应命中: " + sql);
        assertTrue(aw.getParamNameValuePairs().containsValue("byd-news"), "应按 byd-news 过滤: " + sql);
    }

    @Test
    void 详情_通用信源被拒404语义() {
        NewsEntity generic = new NewsEntity();
        generic.setId(9L);
        generic.setSource("source");
        generic.setSourceId(5L);
        when(newsMapper.selectById(9L)).thenReturn(generic);

        assertThrows(IllegalArgumentException.class, () -> service.get(9L));
    }

    @Test
    void 详情_BYD来源放行() {
        NewsEntity byd = new NewsEntity();
        byd.setId(1L);
        byd.setSource("byd-news");
        when(newsMapper.selectById(1L)).thenReturn(byd);
        when(docMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        NewsEntity out = service.get(1L);
        assertEquals(1L, out.getId());
        assertEquals(3L, out.getChunkCount());
    }

    @Test
    void isBydSource_判定() {
        NewsEntity byd = new NewsEntity(); byd.setSource("byd-news");
        NewsEntity legacy = new NewsEntity(); legacy.setSource(null); legacy.setSourceId(null);
        NewsEntity generic = new NewsEntity(); generic.setSource("source"); generic.setSourceId(5L);
        assertTrue(NewsService.isBydSource(byd));
        assertTrue(NewsService.isBydSource(legacy));
        assertFalse(NewsService.isBydSource(generic));
        assertFalse(NewsService.isBydSource(null));
    }
}
