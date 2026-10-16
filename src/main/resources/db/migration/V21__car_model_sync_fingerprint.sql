-- 10-15-car-image-backfill A1:车型主表加同步指纹列
-- 语义:goodsInfo 稳定字段(name/salesNetworkName/vehicleId/price/features/introduce 顺序/carRights)
-- 的 SHA-256(64 hex);NULL = 指纹未初始化(存量兼容,增量分派走轻回填顺带初始化)。
-- 不加 NOT NULL:回填/全量同步后自然填充;已知盲区:goodsParams 独立变化不触发增量(手动同步兜底)。
ALTER TABLE sparkora_car_model ADD COLUMN sync_fingerprint VARCHAR(64);