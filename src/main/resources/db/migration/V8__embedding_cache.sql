-- ============================================================================
-- 10-03 E5:内容寻址嵌入缓存(sparkora_embedding_cache)
--
-- 背景(父任务 10-03-vector-pgstore-kb R6 #6):写路径统一经 EmbeddingBatchRunner
-- → EmbeddingClient.embed(text),每次网络调用;重建(rebuild/rebuild-all)对未变内容
-- 也重新嵌入。新增内容寻址缓存:相同 sha256(text) + 相同 embedding_model 的块复用已有向量。
--
-- 键语义:PRIMARY KEY (content_hash, embedding_model)——同文本换模型天然 miss,
-- 绝不复用旧模型向量(与 09-27 向量模型防护同向)。
-- embedding 用 TEXT 存 pgvector 字面量("[...]"):缓存只做键查回读,不做 ANN,
-- 规避 vector 类型映射(缓存与检索表 vector_store 分离,不参与相似度排序)。
--
-- 约定:单语句、无 DO $$ 块;不改任何 V1–V7。
-- 回退:删缓存表 + revert;embedForIndex 可退回直调 embed。
-- ============================================================================
CREATE TABLE IF NOT EXISTS sparkora_embedding_cache (
    content_hash    CHAR(64)     NOT NULL,   -- sha256(text) 十六进制小写
    embedding_model VARCHAR(100) NOT NULL,  -- EmbeddingClient.modelName()
    embedding       TEXT         NOT NULL,  -- pgvector 字面量 "[...]"
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (content_hash, embedding_model)
);
