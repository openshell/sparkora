-- ============================================================================
-- 10-02-brief-reasoning-maxtokens:附属信息字段重构 + 澄清阶段思考过程落库
--
-- 一、附属信息字段重构(R5/AC6/AC7):
--   - 删除 keywords(关键词):无生效点(仅车型识别输入 + Version prompt),语义被主题+内容描述取代。
--   - 删除 remark(备注):纯存储、不进 prompt、无展示。
--   - extra_info(补充信息)改名为 content_description(内容描述)并升级为全链路生效
--     (创建落库→澄清→研究→简报→深度写作→多版本);存量数据先搬到新列再 DROP 旧列。
--
-- 二、澄清阶段 AI 思考过程(R2):brief 新增 research_reasoning,落 reasoning 模型思考过程,
--     由 /deep/status 增量透出供澄清页展示。
--
-- 约定:Flyway 不支持 DO $$ 块,列搬数用「补列 → UPDATE 搬数 → DROP 旧列」单语句实现;
--       本脚本为一次性迁移,无需 IF NOT EXISTS 兜底(对齐 db/migration/README.md)。
-- 回滚:新增更高版本 V5 反向补列/搬回(已 DROP 的 keywords/remark 无生效点,不回补)。
-- ============================================================================
ALTER TABLE sparkora_article_project ADD COLUMN content_description TEXT;
UPDATE sparkora_article_project SET content_description = extra_info WHERE extra_info IS NOT NULL;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS extra_info;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS keywords;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS remark;
ALTER TABLE sparkora_article_brief ADD COLUMN research_reasoning TEXT;
