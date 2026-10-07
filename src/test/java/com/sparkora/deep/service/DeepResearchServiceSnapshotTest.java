package com.sparkora.deep.service;

import com.sparkora.config.DeepProperties;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.service.SettingService;
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
import static org.mockito.Mockito.when;

/**
 * DeepResearchService 策略快照单测(09-25-brief-web-search R6/AC-05):
 * 运行时设置优先 > 部署级默认;开关两路相与。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchServiceSnapshotTest {

    @Mock SettingService settingService;

    private DeepResearchService service(DeepProperties props) {
        return new DeepResearchService(null, null, null, null, props, null, null, null, null, settingService);
    }

    @Test
    void 运行时设置为空_回退部署级默认() {
        DeepProperties props = new DeepProperties();
        props.setWebProviderOrder("SEARXNG,TAVILY");
        when(settingService.getWebProviderOrder()).thenReturn(null);
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        WebSearchSnapshot s = service(props).resolveSnapshot(7L);
        assertEquals("SEARXNG_FIRST", s.strategyLabel());
        assertTrue(s.webAllowed());
        assertEquals(7L, s.briefId());
    }

    @Test
    void 运行时设置非空_优先于部署级() {
        DeepProperties props = new DeepProperties();
        props.setWebProviderOrder("SEARXNG,TAVILY");   // 部署级要求 SearxNG 优先
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");   // 运行时改为 Tavily 优先
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        WebSearchSnapshot s = service(props).resolveSnapshot(1L);
        assertEquals("TAVILY_FIRST", s.strategyLabel());   // AC-05 运行时优先
    }

    @Test
    void 运行时开关关闭_快照不放行() {
        DeepProperties props = new DeepProperties();
        props.setSearchWebEnabled(true);
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(settingService.isWebSearchEnabled()).thenReturn(false);
        assertFalse(service(props).resolveSnapshot(1L).webAllowed());   // AC-04 运行时门控
    }

    @Test
    void 部署级开关关闭_快照不放行() {
        DeepProperties props = new DeepProperties();
        props.setSearchWebEnabled(false);
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        assertFalse(service(props).resolveSnapshot(1L).webAllowed());   // AC-04 部署级门控
    }

    /** 10-04 B:默认 web-fanout=first_hit,策略 FIRST_HIT(零回归);primary 默认含付费源与 SEARXNG。 */
    @Test
    void 默认策略_FIRST_HIT_primary默认含付费源与SEARXNG() {
        DeepProperties props = new DeepProperties();
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        WebSearchSnapshot s = service(props).resolveSnapshot(1L);
        assertEquals(com.sparkora.deep.search.SearchStrategy.FIRST_HIT, s.strategy());
        assertEquals("TAVILY_FIRST", s.strategyLabel());
        // 快照携带配置的 primary 集合原序(TAVILY,SERPER,SEARXNG);与 order 的交集在 WebSearchRouter 内计算
        assertEquals(List.of(com.sparkora.deep.search.WebProvider.TAVILY,
                        com.sparkora.deep.search.WebProvider.SERPER,
                        com.sparkora.deep.search.WebProvider.SEARXNG), s.primaryProviders());
        assertEquals(List.of("bilibili.com", "weixin.sogou.com"), s.denyDomains());
    }

    /** 10-04 B:web-fanout=primary_fanout → 策略 PRIMARY_FANOUT,标签回落 PRIMARY_FANOUT。 */
    @Test
    void 配置primary_fanout_策略与标签正确() {
        DeepProperties props = new DeepProperties();
        props.setWebFanout("primary_fanout");
        props.setWebPrimaryProviders("TAVILY,SEARXNG");
        when(settingService.getWebProviderOrder()).thenReturn("TAVILY,SEARXNG");
        when(settingService.isWebSearchEnabled()).thenReturn(true);
        WebSearchSnapshot s = service(props).resolveSnapshot(1L);
        assertEquals(com.sparkora.deep.search.SearchStrategy.PRIMARY_FANOUT, s.strategy());
        assertEquals("PRIMARY_FANOUT", s.strategyLabel());
        assertEquals(2, s.primaryProviders().size());
    }

    /** 10-04 B:未知策略值明确拒绝(配置错误暴露),不静默吞掉。 */
    @Test
    void 未知策略值_明确拒绝() {
        DeepProperties props = new DeepProperties();
        props.setWebFanout("bogus");
        assertThrows(IllegalArgumentException.class, props::effectiveSearchStrategy);
    }
}
