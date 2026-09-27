-- ============================================================================
-- 09-27 knowledge-write-unify:向量模型名防护(R5)
--
-- 背景:4 张向量表原先不存模型名/维度(V1 注释「故不存模型名/维度列」)。同维换模型
-- (如 1024 维换另一个 embedding 模型)后旧向量会与新向量静默混空间,检索无法检测。
-- 本迁移推翻该决策:加 embedding_model 列,写入盖当前配置模型,检索按当前模型过滤,
-- 旧模型行自然失效(可见降级而非静默污染)。
--
-- 回填:存量行标记为**实际部署配置**模型(Flyway placeholder ${embeddingModel},由
-- application.yml 的 spring.flyway.placeholders.embeddingModel 绑定 AI_EMBEDDING_MODEL),
-- 避免把既有库错标为硬编码默认值。
-- 列可空(不设 NOT NULL,避免极端数据导致迁移失败);检索用 = 比较,NULL 行天然不匹配。
-- ============================================================================
ALTER TABLE sparkora_car_doc_embedding  ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_kb_chunk_embedding ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_news_doc_embedding ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_image_embedding    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

UPDATE sparkora_car_doc_embedding  SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_kb_chunk_embedding SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_news_doc_embedding SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_image_embedding    SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
