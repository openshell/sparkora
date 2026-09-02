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

-- S3b 增量迁移：版本表挂封面与正文插图（预览/发布按「当前版本」取图，版本间各自独立）
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS cover_image_id BIGINT;
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS body_image_ids VARCHAR(1000);

-- 配图资产表（S3b：图库上传 / 文生图 / 图生图 三来源统一入库；AI 生成图一律转存本地）
CREATE TABLE IF NOT EXISTS sparkora_image_asset (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT,                         -- 关联项目（可空 = 全局图库；MVP 单 workspace 不单设 workspace_id）
    file_name      VARCHAR(255) NOT NULL,         -- 原始文件名（生成图为 prompt 摘要命名）
    storage_path   VARCHAR(500) NOT NULL,         -- 本地相对路径（/images/** 静态映射根下，如 2026/08/uuid.png）
    source         VARCHAR(20)  NOT NULL,         -- upload / ai-text2img / ai-img2img
    prompt_text    TEXT,                          -- 生成 prompt（AI 来源时）
    ref_image_id   BIGINT,                        -- 图生图参考图 id（自引用，可空）
    width          INTEGER,                       -- px，取不到时为空
    height         INTEGER,
    created_by     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_image_asset_project ON sparkora_image_asset(project_id);

-- S3b 增量:全库图支持(project_id 释放为可空)
ALTER TABLE sparkora_image_asset ALTER COLUMN project_id DROP NOT NULL;

-- S4:七牛图床转存 key(懒转存,预览/发布时 ensure;只存 key,URL 由域名实时拼)
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS qiniu_key VARCHAR(300);

-- S5:公众号发布留痕(草稿箱 media_id / 发布主题 / 时间 / 最近一次失败原因)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS publish_media_id VARCHAR(128);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS publish_theme VARCHAR(64);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS last_publish_error VARCHAR(1000);

-- S6:创作项目关联车型(可选;生成 brief/版本时注入车型知识库 RAG 上下文)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS car_model_id BIGINT;

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

-- ============================================================================
--  S6:车型知识库（RAG）
--  数据源:比亚迪官网 4 个公开 JSON API（goodsListForSearch / getGoodsInfoById /
--         goodsParams / getGoodsAttrListForCompareByGoodsId）
--  关系层:car_model / car_version / car_param_group / car_param / car_doc
--  向量层:car_doc_embedding（pgvector, 1024 维, Qwen3-Embedding-8B）
--  切分粒度:仅 PARAM_GROUP（每参数分组一个文档块,供 RAG 检索）
-- ============================================================================

-- pgvector 扩展（需 superuser 预建;sparkora 用户需被授权 CREATE EXTENSION,否则启动失败）
CREATE EXTENSION IF NOT EXISTS vector;

-- 车型主表
CREATE TABLE IF NOT EXISTS sparkora_car_model (
    id              BIGSERIAL PRIMARY KEY,
    goods_id        VARCHAR(32)  NOT NULL UNIQUE,   -- 官网 goodsId,如 156
    name            VARCHAR(100) NOT NULL,          -- 大唐EV
    sales_network   VARCHAR(20),                    -- 王朝 / 海洋
    vehicle_id      VARCHAR(32),                    -- 官网 vehicleId
    price_range     VARCHAR(100),                   -- "239,900 - 309,900"
    features        TEXT,                           -- JSON 数组,卖点
    intro_images    TEXT,                           -- JSON 数组,图片 URL
    detail_page     VARCHAR(200),                   -- 官网详情页路径
    car_rights      TEXT,                           -- JSON,购车权益
    source_url      VARCHAR(300),                   -- 来源官网 URL
    sync_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING/SYNCING/SUCCESS/FAILED
    last_sync_at    TIMESTAMP,
    last_sync_error VARCHAR(1000),
    created_by      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

-- 车型版本表（对应 goodsParams 的「车型」行 + getGoodsAttrList 的价格）
CREATE TABLE IF NOT EXISTS sparkora_car_version (
    id            BIGSERIAL PRIMARY KEY,
    model_id      BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    version_name  VARCHAR(100) NOT NULL,             -- 800KM后驱激光雷达尊荣型
    price         NUMERIC(12,2),                    -- 239900
    price_remark  VARCHAR(100),                     -- "239,900起"
    sort_order    INTEGER      DEFAULT 0,           -- 对应 value[] 下标
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_version_model ON sparkora_car_version(model_id);

-- 参数分组表（对应 goodsParams.configs）
CREATE TABLE IF NOT EXISTS sparkora_car_param_group (
    id          BIGSERIAL PRIMARY KEY,
    model_id    BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    group_name  VARCHAR(100) NOT NULL,             -- 尺寸参数 / 动力性能 / DiPilot智能辅助驾驶
    sort_order  INTEGER      DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_param_group_model ON sparkora_car_param_group(model_id);

-- 参数明细表（对应 goodsParams.configs[].value[]）
CREATE TABLE IF NOT EXISTS sparkora_car_param (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES sparkora_car_param_group(id),
    model_id    BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    param_name  VARCHAR(200) NOT NULL,              -- 长×宽×高(mm) / 轴距(mm)
    param_value TEXT,                              -- 该参数在「当前选中版本」下的值
    values_json TEXT,                              -- JSON 数组,全版本值(保留下标对齐)
    sort_order  INTEGER      DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_param_group ON sparkora_car_param(group_id);
CREATE INDEX IF NOT EXISTS idx_car_param_model ON sparkora_car_param(model_id);

-- 清洗后结构化参数表（S6 重构:规则引擎 + AI 兜底清洗,支撑文章生成干净取值/跨版本对比/数值计算）
CREATE TABLE IF NOT EXISTS sparkora_car_param_clean (
    id            BIGSERIAL PRIMARY KEY,
    param_id      BIGINT       NOT NULL REFERENCES sparkora_car_param(id),  -- 关联原始参数
    model_id      BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    version_id    BIGINT,                          -- 可空:全局参数为空,版本专属指向版本
    param_key     VARCHAR(200) NOT NULL,           -- 规范化参数名,如 轴距 / 纯电续航
    param_value   TEXT,                            -- 清洗后的值(字符串/枚举/布尔)
    value_type    VARCHAR(20)  NOT NULL,           -- STRING / NUMBER / BOOLEAN / ENUM / LIST
    numeric_value NUMERIC,                         -- value_type=NUMBER 时的数值
    unit          VARCHAR(20),                     -- 单位,如 mm / km / kWh
    enum_value    VARCHAR(50),                    -- value_type=ENUM 时的枚举(有/无/可选装)
    list_values   TEXT,                            -- value_type=LIST 时的 JSON 数组
    raw_value     TEXT,                            -- 清洗前原始串(回溯)
    clean_method  VARCHAR(20)  NOT NULL,           -- RULE / AI / RULE_AI
    confidence    NUMERIC(4,3),                    -- AI 清洗置信度
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_param_clean_model ON sparkora_car_param_clean(model_id);
CREATE INDEX IF NOT EXISTS idx_car_param_clean_param ON sparkora_car_param_clean(param_id);

-- 文档块表（RAG 检索单元;chunk_type: MODEL_INFO / PARAM_GROUP / RIGHTS / FEATURE）
CREATE TABLE IF NOT EXISTS sparkora_car_doc (
    id          BIGSERIAL PRIMARY KEY,
    model_id    BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    version_id  BIGINT,                            -- 可空:全局块为空,版本专属块指向版本
    group_id    BIGINT,                            -- 可空:来源参数分组
    chunk_type  VARCHAR(20)  NOT NULL,             -- MODEL_INFO / PARAM_GROUP / RIGHTS / FEATURE
    chunk_text  TEXT         NOT NULL,             -- 切分后的文本块(喂给 embedding 的原文)
    token_count INTEGER,                           -- 文本块 token 数
    sort_order  INTEGER      DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_doc_model ON sparkora_car_doc(model_id);

-- 向量表（pgvector;Qwen3-Embedding-8B 实测 1024 维）
CREATE TABLE IF NOT EXISTS sparkora_car_doc_embedding (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT NOT NULL REFERENCES sparkora_car_doc(id),
    model_id    BIGINT NOT NULL REFERENCES sparkora_car_model(id),
    embedding   VECTOR(1024),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_car_doc_emb_model ON sparkora_car_doc_embedding(model_id);
CREATE INDEX IF NOT EXISTS idx_car_doc_emb_vec ON sparkora_car_doc_embedding
    USING hnsw (embedding vector_cosine_ops);

-- 车型同步任务表(S6 重构:异步任务化,取消全量同步,仅手动指定车型同步)
CREATE TABLE IF NOT EXISTS sparkora_car_sync_job (
    id           BIGSERIAL PRIMARY KEY,
    job_type     VARCHAR(20)  NOT NULL,              -- SELECTED / RETRY
    status       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING', -- RUNNING/SUCCESS/PARTIAL/FAILED
    total        INTEGER      DEFAULT 0,
    success      INTEGER      DEFAULT 0,
    failed       INTEGER      DEFAULT 0,
    failed_items TEXT,                               -- JSON:[{goodsId,name,error}]
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    error_msg    VARCHAR(1000),
    created_by   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_car_sync_job_created ON sparkora_car_sync_job(created_at);
