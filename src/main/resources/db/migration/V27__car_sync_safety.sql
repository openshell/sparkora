-- 10-21-car-sync-safety A1:车型同步数据安全与可靠性
-- 目标:空/半响应不得覆盖既有车型数据、任务可观测可收敛、并发互斥。
-- 只加列/建表,不删除/重命名既有列(回滚代码可保留新列)。

-- 1) 车型同步数据源状态。source_state: OK / EMPTY / INCOMPLETE / STALE;NULL 兼容存量。
--    last_checked_at 每次检查都更新;last_sync_at 只在完整成功后更新(语义不变)。
ALTER TABLE sparkora_car_model ADD COLUMN source_state VARCHAR(20);
ALTER TABLE sparkora_car_model ADD COLUMN sync_note VARCHAR(500);
ALTER TABLE sparkora_car_model ADD COLUMN last_checked_at TIMESTAMP;
COMMENT ON COLUMN sparkora_car_model.source_state IS '官网数据源状态: OK/EMPTY/INCOMPLETE/STALE (NULL=存量未标记)';
COMMENT ON COLUMN sparkora_car_model.sync_note IS '最近一次检查/同步的面向用户短说明';
COMMENT ON COLUMN sparkora_car_model.last_checked_at IS '最近一次官网检查时间(空数据/跳过也更新)';

-- 2) 指纹拆分:基础信息(sync_fingerprint 保留)/ 图片(img+introduce)/ 参数(goodsParams+goodsAttrList)。
ALTER TABLE sparkora_car_model ADD COLUMN image_fingerprint VARCHAR(64);
ALTER TABLE sparkora_car_model ADD COLUMN params_fingerprint VARCHAR(64);

-- 3) 同步任务心跳与取消。heartbeat_at 用于陈旧判定(优先于 started_at),cancel_requested=1 请求停止后续车型。
ALTER TABLE sparkora_car_sync_job ADD COLUMN heartbeat_at TIMESTAMP;
ALTER TABLE sparkora_car_sync_job ADD COLUMN cancel_requested SMALLINT NOT NULL DEFAULT 0;
-- 计数拆分:skipped 单独统计(增量跳过不并入 success)。
ALTER TABLE sparkora_car_sync_job ADD COLUMN skipped INT NOT NULL DEFAULT 0;

-- 4) 应用层互斥锁表(按 goodsId / 全局)。过期时间兜底进程崩溃遗留;owner 用于释放归属校验。
CREATE TABLE sparkora_car_sync_lock (
    lock_key   VARCHAR(120) PRIMARY KEY,
    owner      VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_car_sync_lock_expires ON sparkora_car_sync_lock(expires_at);
