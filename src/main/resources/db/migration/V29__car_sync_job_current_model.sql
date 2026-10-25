-- 10-25-car-ops-observability-p2:任务运行中「当前处理车型」可观测。
-- 可空、无回填(历史行 NULL = 未知/终态),终态由 finish 清空。
ALTER TABLE sparkora_car_sync_job ADD COLUMN current_goods_id VARCHAR(64);
COMMENT ON COLUMN sparkora_car_sync_job.current_goods_id IS '运行中任务当前处理的车型 goods_id(10-25 可观测);终态清空';
