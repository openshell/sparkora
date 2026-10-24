-- 10-24-car-history-created-at:官网 goodsInfo.createdAt(数据创建时间),UI 文案为「创建时间」。
-- 与系统审计 created_at 分离,避免混淆;只加列,可回滚保留。
ALTER TABLE sparkora_car_model ADD COLUMN source_created_at TIMESTAMP;
COMMENT ON COLUMN sparkora_car_model.source_created_at IS '官网 goodsInfo.createdAt(数据创建时间;非上市/发布时间)';
