-- Sparkora S0 schema（幂等：可重复执行）
-- 数据库：PostgreSQL（SPARKORA_DB_* 配置）

-- 用户表
CREATE TABLE IF NOT EXISTS sparkora_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL UNIQUE,
    password     VARCHAR(128) NOT NULL,  -- BCrypt 哈希
    display_name VARCHAR(64),
    role         VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',  -- ADMIN / EDITOR / VIEWER
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 角色表（最简，MVP 用 user.role 字段；此表预留多角色扩展）
CREATE TABLE IF NOT EXISTS sparkora_role (
    id    BIGSERIAL PRIMARY KEY,
    code  VARCHAR(20) NOT NULL UNIQUE,  -- ADMIN / EDITOR / VIEWER
    name  VARCHAR(64)
);

-- 创作项目表
CREATE TABLE IF NOT EXISTS sparkora_article_project (
    id                       BIGSERIAL PRIMARY KEY,
    topic                    VARCHAR(200) NOT NULL,
    keywords                 VARCHAR(500),
    audience                 VARCHAR(200),
    word_count_target        INTEGER,
    brand_voice_profile_id   BIGINT,                -- S0 先存不启用
    status                   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT/GENERATING_BRIEF/READY/...
    current_brief_id         BIGINT,                -- S1：指向当前 brief（sparkora_article_brief.id）
    last_brief_error         VARCHAR(1000),         -- S1：最近一次生成失败原因（成功后清空）
    remark                   VARCHAR(500),
    created_by               VARCHAR(64)  NOT NULL,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  SMALLINT     NOT NULL DEFAULT 0        -- 逻辑删除
);

-- 创作 Brief 表（S1：一个项目可多次重生成，保留历史；project.current_brief_id 指向当前）
CREATE TABLE IF NOT EXISTS sparkora_article_brief (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    title_candidates   TEXT,        -- JSON 数组 ["标题1","标题2"]
    audience_refine    VARCHAR(500),-- AI 细化的目标读者
    core_viewpoints    TEXT,        -- JSON 数组 ["观点1","观点2"]
    outline            TEXT,        -- JSON 数组 [{heading, subPoints:[...]}]
    fact_risks         TEXT,        -- JSON 数组 [{claim, riskLevel, suggestion}]
    ai_model           VARCHAR(64), -- 实际使用的模型
    token_usage        INTEGER,     -- total tokens
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_brief_project ON sparkora_article_brief(project_id);

-- S1 增量迁移（已部署的旧库通过 ALTER 补列；IF NOT EXISTS 幂等，新库执行也无副作用）
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS current_brief_id BIGINT;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS last_brief_error VARCHAR(1000);

-- 文章版本表（S1b：基于 brief 循环生成 2-3 版正文，风格各异；project.current_version_id 指向选定版）
CREATE TABLE IF NOT EXISTS sparkora_article_version (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    brief_id           BIGINT,                       -- 基于哪个 brief 生成
    title              VARCHAR(200),                 -- 该版本标题（可不同于 brief 候选）
    content_md         TEXT,                         -- 正文 Markdown
    version_label      VARCHAR(10),                  -- A / B / C
    style_tag          VARCHAR(20),                  -- 风格标记：正式 / 活泼 / 干货 等
    ai_model           VARCHAR(64),
    token_usage        INTEGER,
    word_count         INTEGER,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_version_project ON sparkora_article_version(project_id);

-- S1b 增量迁移
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS current_version_id BIGINT;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS last_version_error VARCHAR(1000);

-- 风格库表（S2：用户提供的文章由 AI 提炼为风格画像入库；生成版本时用户从库中选风格）
CREATE TABLE IF NOT EXISTS sparkora_style_profile (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(64)  NOT NULL,         -- 风格名（用户可改）
    description    VARCHAR(500),                  -- 风格简述
    tone_guidance  TEXT,                          -- 提供给生成模型的语气/结构指令（system prompt 片段）
    source_excerpt TEXT,                          -- 提炼自哪段原文（截断保留，便于回溯）
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 预置角色（幂等插入）
INSERT INTO sparkora_role (code, name)
SELECT 'ADMIN', '管理员'
WHERE NOT EXISTS (SELECT 1 FROM sparkora_role WHERE code = 'ADMIN');
INSERT INTO sparkora_role (code, name)
SELECT 'EDITOR', '编辑'
WHERE NOT EXISTS (SELECT 1 FROM sparkora_role WHERE code = 'EDITOR');
INSERT INTO sparkora_role (code, name)
SELECT 'VIEWER', '只读'
WHERE NOT EXISTS (SELECT 1 FROM sparkora_role WHERE code = 'VIEWER');

-- 预置管理员由 DataInitializer 启动时用 BCryptPasswordEncoder 生成哈希后插入（不在此硬编码哈希）
