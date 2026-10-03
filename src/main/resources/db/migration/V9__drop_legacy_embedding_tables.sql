-- ============================================================================
-- 10-03 E6:旧 4 张向量表退役(消除双写/双读)
--
-- 背景:E1 引入单表 vector_store(metadata.domain)后,旧 4 表仍被双写(每次 rebuild 写旧表 +
-- store)且被 vector-stats / EmbeddingModelReconcileRunner / ImageEmbeddingService.rebuildMissing
-- 读取。E6 已移除全部旧表读写(写路径只写 store;读路径改查 store),旧表与 store 逐字节等价
-- (E1 阶段 A 已证),故本迁移物理删除旧表,使向量层单一只真源。
--
-- 不可逆:仅在前序代码退役 + 全绿 + 对拍后执行(本任务最后一步)。回退 = 由 store 重建旧表
-- (向量逐字节可复现)或 git revert 本迁移 + 备份/重嵌恢复。
--
-- 命名说明:sparkora_car_doc_embedding 的表名**未随 E4 重命名**(E4 只改主表
-- sparkora_car_doc → sparkora_car_chunk),故此处仍按旧名删除。
-- 单语句 DROP TABLE,无 DO $$ 块;不改任何 V1–V8。
-- ============================================================================
DROP TABLE IF EXISTS sparkora_car_doc_embedding;
DROP TABLE IF EXISTS sparkora_kb_chunk_embedding;
DROP TABLE IF EXISTS sparkora_news_doc_embedding;
DROP TABLE IF EXISTS sparkora_image_embedding;
