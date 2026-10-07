package com.sparkora.source.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 信源动态调度启动注册(10-05-source-crawl-base)。应用就绪后按注册表逐源注册/注销任务。
 *
 * <p>总开关关闭({@code SOURCE_COLLECT_ENABLED=false})时不注册任何 trigger(零回归)。
 * 异常仅 warn 不阻断启动(与补齐 runner 同容错先例)。
 */
@Slf4j
@Component
@Order(30)
public class SourceScheduleRunner implements ApplicationRunner {

    private final SourceScheduleService scheduleService;

    public SourceScheduleRunner(SourceScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            scheduleService.registerAll();
        } catch (Exception e) {
            log.warn("信源调度启动注册失败(不阻断启动): {}", e.getMessage());
        }
    }
}
