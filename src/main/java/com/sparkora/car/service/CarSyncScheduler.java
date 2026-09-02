package com.sparkora.car.service;

import com.sparkora.config.CarProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 车型知识库定时同步调度。
 * 开关 CAR_SYNC_ENABLED(.env),默认关闭;开启后按 CAR_SYNC_CRON 每日全量同步。
 */
@Slf4j
@Component
public class CarSyncScheduler {

    private final CarProperties props;
    private final CarModelService service;

    public CarSyncScheduler(CarProperties props, CarModelService service) {
        this.props = props;
        this.service = service;
    }

    @Scheduled(cron = "${sparkora.car.sync-cron:0 0 3 * * ?}")
    public void sync() {
        if (!props.isSyncEnabled()) {
            log.debug("车型定时同步未开启(CAR_SYNC_ENABLED=false),跳过");
            return;
        }
        log.info("车型定时同步开始");
        try {
            int n = service.syncAll();
            log.info("车型定时同步完成,入库 {} 个车型", n);
        } catch (Exception e) {
            log.error("车型定时同步失败: {}", e.getMessage(), e);
        }
    }
}
