-- ============================================================================
-- C1 / 10-03-gen-cognitive-redesign:认知层重构(意图澄清对话)
--
-- 背景:把「LLM 一次性定长澄清问卷」改造为多轮对话式意图澄清。brief 侧新增:
--   - clarify_session TEXT  多轮会话 JSON {status,slots[],turns[],currentQuestion,converged}
--   - task_brief      TEXT  结构化意图契约 JSON {purpose,audience,...,slotMeta[]}(每槽位带 source/confidence)
--   - clarify_status  VARCHAR(20)  澄清会话态 ASKING/CONVERGED/ABORTED(仅 DEEP 链路使用)
--
-- 并发兜底:部分唯一索引约束同一项目同时至多一条 ASKING 会话(与 uq_brief_planning 同范式);
--           历史/完成行(clarify_status 为 null/CONVERGED/ABORTED)不冲突,可安全对存量库执行。
--
-- 约定:JSON 列一律 TEXT 存字符串,不引 JSON 类型处理器;无 DO $$ 块;不改任何 V1–V9。
-- 回滚:新增更高版本反向 DROP 三列与索引(数据可清空,风险低)。
-- ============================================================================
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS clarify_session TEXT;
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS task_brief TEXT;
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS clarify_status VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS uq_brief_clarify_asking
    ON sparkora_article_brief(project_id) WHERE clarify_status = 'ASKING';
