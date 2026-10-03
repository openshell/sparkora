-- ============================================================================
-- 10-03 E1:向量层迁移到 Spring AI PgVectorStore(单表 + metadata.domain)
--
-- 背景(父任务 10-03-vector-pgstore-kb R1):4 域向量检索原先分散在 4 张
-- sparkora_{car_doc,kb_chunk,news_doc,image}_embedding 表 + 手写注解 SQL(含 JOIN 活表)。
-- E1 阶段 A 目标:把「存储 + 相似度检索」替换为 Spring AI PgVectorStore 单表
-- (metadata.domain 过滤),切块算法与业务规则(锚点/配额/门槛/四态)语义不变,
-- 同 query 集逐条对拍一致。
--
-- 契约(design §3.1):
--   content   = chunk_text(行内注入正文)
--   metadata  = {domain, refId, modelId, chunkType, name, active, embeddingModel}
--               domain ∈ CAR/KB/NEWS/IMAGE
--               refId  = 域内 id(CAR=car_doc.id / KB=kb_chunk.id / NEWS=news_doc.id / IMAGE=image_asset.id)
--               modelId 仅 CAR(锚点加权;其余省略键)
--   embedding = vector(1024),COSINE(与旧表 vector_cosine_ops 一致)
--
-- 建表形状严格对齐 PgVectorStore 期望(反编译 2.0.1 确认):
--   id uuid PRIMARY KEY, content text, metadata json, embedding vector(%d)
-- PgVectorStore DDL 里 id 用 `uuid DEFAULT uuid_generate_v4()`;本部署未安装 uuid-ossp
-- 扩展且 store 每次写入都显式提供 id(Document.id),故不设 DEFAULT(避免依赖不可用扩展)。
-- initializeSchema=false 由 Flyway 管理建表,不用 Spring 自动建表。
--
-- 旧 4 表不删(R8 可回退):回填 runner 从旧表复用已算好的向量,不重新嵌入。
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1024)
);

-- COSINE 检索索引:与旧表 idx_*_emb_vec_hnsw 同为 HNSW vector_cosine_ops
CREATE INDEX IF NOT EXISTS idx_vector_store_emb_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- metadata 过滤(domain/active/embeddingModel/refId)加速:查询用 `metadata::jsonb @@ jsonpath`
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_gin
    ON vector_store USING gin ((metadata::jsonb) jsonb_path_ops);
