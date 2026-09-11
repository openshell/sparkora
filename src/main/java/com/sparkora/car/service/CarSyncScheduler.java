package com.sparkora.car.service;

import com.sparkora.config.CarProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 车型定时增量同步(C1)。
 *
 * 以官网目录全量为准,逐车型幂等 upsert(既有 syncOne 能力),实现「新增车型自动入库 + 存量刷新」。
 * 默认关闭(sync-enabled=false),需在 .env 显式开启;cron 默认每天 03:00。
 * 运行中任务存在时跳过本轮,避免与手动任务重叠。
 */
@Slf4j
@Component
public class CarSyncScheduler {

    private final CarProperties props;
    private final CarSyncJobService jobService;
    private final CarModelService modelService;

    public CarSyncScheduler(CarProperties props, CarSyncJobService jobService, CarModelService modelService) {
        this.props = props;
        this.jobService = jobService;
        this.modelService = modelService;
    }

    @Scheduled(cron = "${sparkora.car.sync-cron:0 0 3 * * ?}")
    public void scheduledSync() {
        if (!props.isSyncEnabled()) return;
        try {
            if (jobService.hasRunning()) {
                log.warn("定时车型同步跳过:已有运行中的同步任务");
                return;
            }
            List<String> goodsIds = new ArrayList<>();
            for (Map<String, Object> item : modelService.catalog()) {
                Object id = item.get("id");   // 官网目录主键即 goodsId
                if (id != null && !String.valueOf(id).isBlank()) goodsIds.add(String.valueOf(id));
            }
            if (goodsIds.isEmpty()) {
                log.warn("定时车型同步跳过:官网目录为空");
                return;
            }
            Long jobId = jobService.createJob(goodsIds, "SCHEDULED");
            log.info("定时车型同步已创建任务 jobId={} 车型数={}", jobId, goodsIds.size());
        } catch (Exception e) {
            log.error("定时车型同步失败: {}", e.getMessage(), e);
        }
    }
}
