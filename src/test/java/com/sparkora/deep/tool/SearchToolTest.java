package com.sparkora.deep.tool;

import com.sparkora.car.service.CarRagService;
import com.sparkora.config.DeepProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchTool 降级行为单测:KB 永可用/SEARXNG 空结果降级/Tavily 未配置不可用。
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
    void SEARXNG_空结果_标记不可用() {
        SearxngSearchTool tool = new SearxngSearchTool(
                new org.springframework.mock.env.MockEnvironment().withProperty("SEARXNG_BASE_URL", "http://127.0.0.1:1"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(tool.available(), "初始可用");
        List<SearchTool.SearchHit> hits = tool.search("test", 5);   // 连接失败 → 空
        assertTrue(hits.isEmpty());
        assertEquals(false, tool.available(), "调用失败后惰性降级");
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
}