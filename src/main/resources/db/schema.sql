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
    remark                   VARCHAR(500),
    created_by               VARCHAR(64)  NOT NULL,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  SMALLINT     NOT NULL DEFAULT 0        -- 逻辑删除
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
