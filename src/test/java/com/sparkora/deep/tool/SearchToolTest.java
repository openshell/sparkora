package com.sparkora.deep.tool;

import com.sparkora.car.service.CarRagService;
import com.sparkora.config.DeepProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchTool 行为单测:KB 永可用/SEARXNG 失败仅记健康态不闩锁/Tavily 未配置不可用。
 */
class SearchToolTest {

    @Test
    void KB工具_检索异常_返回空列表不抛出() {
        // ragService 传 null 模拟异常路径(KnowledgeSearchTool 内部 catch)
        KnowledgeSearchTool tool = new KnowledgeSearchTool(null);
        List<SearchTool.SearchHit> hits = tool.search("q", 5);
        assertTrue(hits.isEmpty());
        assertTrue(tool.available());
        assertEquals("KB", tool.name());
    }

    @Test
    void SEARXNG_空结果_仅标记健康态不闩锁() {
        SearxngSearchTool tool = new SearxngSearchTool(
                new org.springframework.mock.env.MockEnvironment().withProperty("SEARXNG_BASE_URL", "http://127.0.0.1:1"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(tool.available(), "初始可用");
        assertTrue(tool.lastCallOk(), "初始乐观");
        List<SearchTool.SearchHit> hits = tool.search("test", 5);   // 连接失败 → 空
        assertTrue(hits.isEmpty());
        assertEquals(false, tool.lastCallOk(), "失败仅记健康态");
        assertTrue(tool.available(), "可用性只判配置就绪,失败可自恢复");
    }

    @Test
    void TAVILY_有密钥_可用性只判配置不闩锁() {
        TavilySearchTool tool = new TavilySearchTool(new DeepProperties() {
            @Override public String effectiveTavilyKey() { return "dummy-key"; }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(tool.available(), "有 key 即可用");
        assertTrue(tool.configured(), "配置就绪");
        assertTrue(tool.lastCallOk(), "未调用过乐观为真");
    }

    @Test
    void TAVILY_未配置密钥_行为安全() {
        DeepProperties props = new DeepProperties();
        // 注意:effectiveTavilyKey 会兜底读环境变量(docker 环境可能已配);本用例验证的是
        // 「密钥为空时 available=false、search 返回空不抛出」的容错契约,用独立构造的空密钥实例
        TavilySearchTool tool = new TavilySearchTool(new DeepProperties() {
            @Override public String effectiveTavilyKey() { return ""; }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(!tool.available());
        assertTrue(tool.search("q", 3).isEmpty());
    }

    // ===== 10-04-serper-provider A:searchVertical 默认实现(不破坏既有实现) =====

    /** A-R3/AC-A9:不支持垂直的工具有默认实现(委托 search),SearxNG/KB 零改动即合规。 */
    @Test
    void searchVertical默认实现_委托search_行为等价() {
        // SearxngSearchTool 未覆写 searchVertical → 默认委托 search(连接失败返回空,不抛)
        SearxngSearchTool tool = new SearxngSearchTool(
                new org.springframework.mock.env.MockEnvironment().withProperty("SEARXNG_BASE_URL", "http://127.0.0.1:1"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(tool.search("q", 5), tool.searchVertical("q", "news", 5), "默认实现等价 search");
        assertEquals(tool.search("q", 5), tool.searchVertical("q", "web", 5));
    }

    @Test
    void SERPER_有密钥_可用性只判配置不闩锁() {
        SerperSearchTool tool = new SerperSearchTool(new DeepProperties() {
            @Override public String effectiveSerperKey() { return "dummy-key"; }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("SERPER", tool.name());
        assertTrue(tool.available(), "有 key 即可用");
        assertTrue(tool.configured(), "配置就绪");
        assertTrue(tool.lastCallOk(), "未调用过乐观为真");
    }
}