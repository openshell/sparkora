-- ============================================================================
-- C3 / 10-03-gen-cognitive-redesign:简报写作蓝图(写作蓝图 + 人工评审门)
--
-- 背景:简报定位从「事实手册摘要」改为「写作蓝图」。基于 TaskBrief + research_plan + fact_sheet,
--       产出 thesis + 论证结构 + evidenceMap(绑定 fact_sheet entry key)+ narrativeArc + gaps + 质量信号,
--       并设人工评审门(REVIEWING → 用户确认 CONFIRMED 后才解锁写作)。brief 侧新增:
--   - writing_blueprint TEXT  写作蓝图 JSON {thesis,argumentStructure[],evidenceMap[],narrativeArc,constraints[],gaps[],quality}
--   - blueprint_status VARCHAR(20)  蓝图评审门态 REVIEWING/CONFIRMED(仅 DEEP 链路使用)
--   - blueprint_quality TEXT  质量信号 JSON {argumentDensity,evidenceCoverage,gapCount,taskBriefConsistency}
--
-- 约定:JSON 列一律 TEXT 存字符串,不引 JSON 类型处理器;无 DO $$ 块;不改任何 V1–V10。
-- 评审门不新增项目状态位(写权不扩散),用 brief 侧 blueprint_status 表达;项目状态机不变。
-- 回滚:新增更高版本反向 DROP 三列(数据可清空,风险低)。
-- ============================================================================
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS writing_blueprint TEXT;
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS blueprint_status VARCHAR(20);
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS blueprint_quality TEXT;
