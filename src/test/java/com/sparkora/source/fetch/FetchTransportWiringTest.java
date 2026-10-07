package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 抓取通道 Spring 装配回归(10-05-crawl4ai-transport)。本仓无 {@code @SpringBootTest},
 * 多构造器 {@code @Component} 若漏 {@code @Autowired} 会导致应用启动失败但 {@code mvn test} 全绿
 * (Check 阶段实测发现,已修复)。此测试锁定两个 transport 可被容器实例化。
 */
class FetchTransportWiringTest {

    @Test
    void 两个transport可被Spring装配_多构造器须显式Autowired() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(Crawl4aiProperties.class);
            ctx.register(Crawl4aiClient.class);
            ctx.register(Crawl4aiFetchTransport.class);
            ctx.register(HttpFetchTransport.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(Crawl4aiFetchTransport.class), "Crawl4aiFetchTransport 应可装配");
            assertNotNull(ctx.getBean(HttpFetchTransport.class), "HttpFetchTransport 应可装配");
        }
    }
}
