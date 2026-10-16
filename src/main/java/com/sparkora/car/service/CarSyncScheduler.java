package com.sparkora.car.service;

import com.sparkora.domain.entity.SettingEntity;
import com.sparkora.service.SettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 车型定时同步调度器(10-15-car-image-backfill R3:运行时设置驱动,替代 .env 直读)。
 *
 * 改造说明:原实现 @Scheduled(cron = "${sparkora.car.sync-cron:...}") 直读 CarProperties,
 * 开关/频率改配置需重启;现改为 {@code @Scheduled(cron = "0 * * * * ?")} 每分钟 tick:
 *   1. 读 SettingService.get()(自带内存缓存,tick 无逐分钟 DB 压力)→ carSyncEnabled=false 早退;
 *   2. 维护 nextRunAt:CronExpression.parse(carSyncCron).next(now) 计算到点,
 *      cron 字符串变化(或首次装载)即重算(装载轮不开跑);
 *   3. 到点执行增量的「原逻辑」编排(markStaleRunningAsFailed → hasFreshRunning 防重叠 →
 *      官网目录取 goodsId 列表 → 创建 INCREMENTAL 增量任务),完成后顺延下一拍(跳过/失败也顺延,
 *      避免同拍重复触发);任何异常 log.error 不中断 tick。
 *
 * 增量分派语义(跳过/轻回填/全量)见 CarModelService.dispatchIncremental;
 * 设置改动免重启生效(下一 tick 重算);.env 的 CAR_SYNC_ENABLED/CRON 降级为遗留种子,不再被读取。
 */
@Slf4j
@Component
public class CarSyncScheduler {

    private final SettingService settingService;
    private final CarSyncJobService jobService;
    private final CarModelService modelService;

    /** 下一拍执行时刻(由运行时设置 car_sync_cron 推算;内存内维护,重启后按首轮装载重算)。 */
    private volatile LocalDateTime nextRunAt;

    /** 当前已装载的 cron(与设置值比对,变化即重算 nextRunAt)。 */
    private volatile String activeCron;

    public CarSyncScheduler(SettingService settingService, CarSyncJobService jobService, CarModelService modelService) {
        this.settingService = settingService;
        this.jobService = jobService;
        this.modelService = modelService;
    }

    /** 每分钟 tick(固定分钟网格;是否真正执行由运行时设置开关 + cron 到点判定)。 */
    @Scheduled(cron = "0 * * * * ?")
    public void scheduledSync() {
        tick(LocalDateTime.now());
    }

    /** tick 主体(now 由真实时钟注入;包级可见便于单测传入虚拟时刻,免真实等待)。 */
    void tick(LocalDateTime now) {
        try {
            SettingEntity setting = settingService.get();   // SettingService 内存缓存,读多无 DB 压力
            if (!Boolean.TRUE.equals(setting.getCarSyncEnabled())) return;
            String cron = setting.getCarSyncCron();
            if (cron == null || cron.isBlank()) {
                log.warn("车型库定时同步未配置 cron,本轮跳过");
                return;
            }
            String cronTrim = cron.trim();
            if (!cronTrim.equals(activeCron) || nextRunAt == null) {
                // cron 变化(或首次装载):重算下一拍,本轮不开跑
                try {
                    nextRunAt = CronExpression.parse(cronTrim).next(now);
                    activeCron = cronTrim;
                    log.info("车型库定时同步已装载 cron={} 下一拍={}", activeCron, nextRunAt);
                } catch (IllegalArgumentException e) {
                    log.error("车型库定时同步 cron 非法,本轮跳过: {} ({})", cronTrim, e.getMessage());
                    nextRunAt = null;
                    activeCron = null;
                }
                return;
            }
            if (now.isBefore(nextRunAt)) return;
            // 到点执行;无论成败(跳过/异常)均顺延下一拍(按本次到点基准推算),避免同拍重复触发;
            // 执行超长跨拍时 nextRunAt 已过 → 下一 tick 立即补跑(有 hasFreshRunning 防重叠守护)
            try {
                executeIncrementalOnce();
            } finally {
                try {
                    nextRunAt = activeCron == null ? null : CronExpression.parse(activeCron).next(now);
                } catch (IllegalArgumentException e) {
                    log.error("车型库定时同步顺延下一拍失败: {}", e.getMessage());
                    nextRunAt = null;
                }
            }
        } catch (Exception e) {
            log.error("车型库定时同步 tick 失败: {}", e.getMessage(), e);
        }
    }

    /** 到点执行一次增量同步(先清陈旧 RUNNING 自愈,再判未过期重叠,最后按官网目录全量建 INCREMENTAL 任务)。 */
    private void executeIncrementalOnce() {
        // 先清理进程死亡遗留的陈旧 RUNNING(超 60 分钟),否则定时增量会被永久阻塞
        jobService.markStaleRunningAsFailed();
        if (jobService.hasFreshRunning()) {
            log.warn("定时车型增量同步跳过:已有运行中的同步任务");
            return;
        }
        List<String> goodsIds = new ArrayList<>();
        for (Map<String, Object> item : modelService.catalog()) {
            Object id = item.get("id");   // 官网目录主键即 goodsId
            if (id != null && !String.valueOf(id).isBlank()) goodsIds.add(String.valueOf(id));
        }
        if (goodsIds.isEmpty()) {
            log.warn("定时车型增量同步跳过:官网目录为空");
            return;
        }
        Long jobId = jobService.createJob(goodsIds, "INCREMENTAL");
        log.info("定时车型增量同步已创建任务 jobId={} 车型数={}", jobId, goodsIds.size());
    }
}
