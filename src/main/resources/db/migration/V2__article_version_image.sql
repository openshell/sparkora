-- ============================================================================
-- P1-⑦ body_image_ids（逗号列，违反 1NF）规范化为一对多关联表。
-- 原形态：sparkora_article_version.body_image_ids VARCHAR(1000)（有序 id 串），
--   读侧两份重复 split(",")、引用检查用 LIKE 粗筛（id=5 会误匹配 15/51）。
-- 新形态：sparkora_article_version_image 一行一图，sort_order 保序；引用检查走精确 SQL。
-- 应用层维护外键（不建强 FK，同 sparkora_image_tag / embedding 表范式）；无 deleted 列（物理删）。
-- 单条幂等语句；不能用 DO $$ 块（Spring ScriptUtils 不支持 dollar-quote）。
-- 回滚（manual）：重加列 + 从关联表 string_agg(image_id::text, ',' ORDER BY sort_order) 聚合回填。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sparkora_article_version_image (
    id          BIGSERIAL PRIMARY KEY,
    version_id  BIGINT       NOT NULL,               -- → sparkora_article_version.id（应用层维护，不建强 FK）
    image_id    BIGINT       NOT NULL,               -- → sparkora_image_asset.id（应用层维护，不建强 FK）
    sort_order  INTEGER      NOT NULL DEFAULT 0,     -- 正文插图顺序（0 起，与原逗号串顺序一致）
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, image_id)                    -- 数据库级防重（重复登记幂等）
);
CREATE INDEX IF NOT EXISTS idx_article_version_image_version ON sparkora_article_version_image(version_id);
CREATE INDEX IF NOT EXISTS idx_article_version_image_image   ON sparkora_article_version_image(image_id);

-- 回填：逗号串按原顺序展开（trim 后非空 token），WITH ORDINALITY 保序，sort_order 0 起。
-- ON CONFLICT DO NOTHING 兜底重复 id（原串若有重复，仅保留首个顺序）。
INSERT INTO sparkora_article_version_image (version_id, image_id, sort_order)
SELECT v.id,
       trim(parts.token)::bigint,
       parts.ord - 1
FROM sparkora_article_version v
CROSS JOIN LATERAL regexp_split_to_table(v.body_image_ids, ',') WITH ORDINALITY AS parts(token, ord)
WHERE v.body_image_ids IS NOT NULL
  AND trim(v.body_image_ids) <> ''
  AND trim(parts.token) <> ''
ON CONFLICT (version_id, image_id) DO NOTHING;

-- 数据已搬入关联表，删除旧逗号列（回滚见文件头）。
ALTER TABLE sparkora_article_version DROP COLUMN IF EXISTS body_image_ids;
