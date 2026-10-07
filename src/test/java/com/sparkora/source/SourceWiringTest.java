package com.sparkora.source;

import com.sparkora.config.SourceProperties;
import com.sparkora.mapper.SourceMapper;
import com.sparkora.source.service.SourceJobService;
import com.sparkora.source.service.SourceScheduleService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.mockito.Mockito.mock;

/**
 * 信源采集 Spring 装配回归(10-05-source-crawl-base)。本仓无 {@code @SpringBootTest},
 * 多构造器 {@code @Component} 若漏 {@code @Autowired} 会导致应用启动失败但 {@code mvn test} 全绿
 * (先例 {@code FetchTransportWiringTest})。此探针锁定 {@link SourceScheduleService} 可被容器实例化
 * ——它有「生产构造器 + 包级测试构造器」两个构造器,漏 {@code @Autowired} 即在此暴露。
 */
class SourceWiringTest {

    @Test
    void 动态调度服务可被Spring装配_多构造器须显式Autowired() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            ctx.registerBean(SourceProperties.class);
            ctx.registerBean(TaskScheduler.class, () -> scheduler);
            ctx.registerBean(SourceMapper.class, () -> mock(SourceMapper.class));
            ctx.registerBean(SourceJobService.class, () -> mock(SourceJobService.class));
            ctx.register(SourceScheduleService.class);
            ctx.refresh();

            Assertions.assertNotNull(ctx.getBean(SourceScheduleService.class), "SourceScheduleService 应可装配");
        }
    }
}
