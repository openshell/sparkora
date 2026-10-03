-- ============================================================================
-- 10-03 E3:知识库数据模型规范化(受控 domain + 来源/标签/生效期)
--
-- 背景(父任务 10-03-vector-pgstore-kb R6):KB 域原 domain 为自由文本,缺来源/标签/生效期。
-- 本迁移补齐数据模型(仅数据模型,不含运营/导入/审核流):
--   1) sparkora_kb_doc 加 source / effective_from / effective_to(可空 = 不限);
--   2) 存量 domain 收敛到受控词表(精确匹配,否则归「通用」);
--   3) 建标签关联表 sparkora_kb_doc_tag(镜像 sparkora_image_tag:应用层维护、无强 FK、UNIQUE 防重)。
--
-- 受控词表(代码常量 com.sparkora.kb.KbDomain):通用/充电/保养/政策/技术科普/安全/驾驶。
-- 生效期语义:检索可用性由 vector_store metadata.active 承载
--   active = enabled && 今天∈[effective_from,effective_to];由 KbEffectiveWindowReconciler 按日重算。
--
-- 约定:Flyway 脚本按版本只执行一次,不写 DO $$ 块(Spring ScriptUtils 不支持 dollar-quote);
--       列可空保持既有数据向后兼容。回滚:新列可空、关联表独立,revert 无数据破坏。
-- ============================================================================
ALTER TABLE sparkora_kb_doc ADD COLUMN IF NOT EXISTS source         VARCHAR(200);
ALTER TABLE sparkora_kb_doc ADD COLUMN IF NOT EXISTS effective_from DATE;
ALTER TABLE sparkora_kb_doc ADD COLUMN IF NOT EXISTS effective_to   DATE;

-- 存量 domain 回填:受控词表内保留,否则归「通用」(含 NULL)。
UPDATE sparkora_kb_doc
   SET domain = '通用'
 WHERE domain IS NULL
    OR domain NOT IN ('通用', '充电', '保养', '政策', '技术科普', '安全', '驾驶');

-- 标签关联表(镜像 sparkora_image_tag):doc↔标签,按名称使用,不建标签字典表。
CREATE TABLE IF NOT EXISTS sparkora_kb_doc_tag (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL,               -- → sparkora_kb_doc.id(应用层维护,不建强 FK)
    tag_name    VARCHAR(50)  NOT NULL,               -- 标签名(trim 后 1~50 字符)
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (doc_id, tag_name)
);
CREATE INDEX IF NOT EXISTS idx_kb_doc_tag_name ON sparkora_kb_doc_tag(tag_name);
