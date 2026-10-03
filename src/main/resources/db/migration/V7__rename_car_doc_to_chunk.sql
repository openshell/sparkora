-- ============================================================================
-- 10-03 E4:命名规范化 sparkora_car_doc → sparkora_car_chunk(块语义)
--
-- 背景:sparkora_car_doc 实为车型**块**表(含 chunk_text/chunk_type),命名失真;
-- 重命名为块语义。对外契约不变(仅内部命名),旧向量表 sparkora_car_doc_embedding
-- 保持不动(由父任务收尾删除)。
--
-- 注意:PG 重命名表**不会**自动重命名索引(仅保留附件关系),故主键与非主键
-- 索引名都需显式同步为块语义。单语句、无 DO $$ 块;不改任何 V1–V6。
-- ============================================================================
ALTER TABLE sparkora_car_doc RENAME TO sparkora_car_chunk;
ALTER INDEX IF EXISTS idx_car_doc_model RENAME TO idx_car_chunk_model;
ALTER INDEX IF EXISTS sparkora_car_doc_pkey RENAME TO sparkora_car_chunk_pkey;
