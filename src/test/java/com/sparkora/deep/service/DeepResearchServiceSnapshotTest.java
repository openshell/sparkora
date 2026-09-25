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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
