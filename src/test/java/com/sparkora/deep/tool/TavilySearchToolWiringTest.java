package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavily 双端点 Spring 装配回归(10-05-tavily-endpoint-priority)。本仓无 {@code @SpringBootTest},
 * 多构造器 {@code @Component} 若漏 {@code @Autowired} 会导致应用启动失败但 {@code mvn test} 全绿。
 * 此测试锁定 {@code TavilySearchTool} 可被容器实例化(生产构造器须显式 {@code @Autowired})。
 */
class TavilySearchToolWiringTest {

    @Test
    void TavilySearchTool可被Spring装配_多构造器须显式Autowired() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(DeepProperties.class);
            ctx.registerBean(ObjectMapper.class);
            ctx.register(TavilySearchTool.class);
            ctx.refresh();

            TavilySearchTool tool = ctx.getBean(TavilySearchTool.class);
            assertNotNull(tool, "TavilySearchTool 应可装配");
            assertTrue(tool.officialConfigured() || tool.relayConfigured() || !tool.configured(),
                    "装配后端点探针可调用");
            assertNotNull(tool.extractClient(), "extract 独立客户端已构建");
        }
    }
}
