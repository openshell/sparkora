-- 10-15-car-image-backfill A2:运行时车型库定时同步设置(sparkora_setting 单行表扩列)
-- car_sync_enabled / car_sync_cron 为运行时设置(CarSyncScheduler 每分钟 tick 读取,改即生效,免重启);
-- .env 的 CAR_SYNC_ENABLED/CAR_SYNC_CRON 降级为遗留种子(不再被调度读取,仅存量配置兼容)。
-- NOT NULL + DEFAULT:存量单行由 ALTER 默认值填充,无需数据种子。
ALTER TABLE sparkora_setting
    ADD COLUMN car_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN car_sync_cron VARCHAR(64) NOT NULL DEFAULT '0 0 3 * * ?';