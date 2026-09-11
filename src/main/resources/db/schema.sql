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
    rag_status         VARCHAR(20), -- S6.1:知识库检索状态 OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_brief_project ON sparkora_article_brief(project_id);

-- S6.1 增量迁移(已部署旧库幂等补列):知识库检索状态随 brief/version 落库供前端展示
ALTER TABLE sparkora_article_brief   ADD COLUMN IF NOT EXISTS rag_status VARCHAR(20);

-- S9 增量迁移(深度生成模式):产物随 brief 落库,可断点续跑(gen_mode=DEEP 时使用,快速模式全空)
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS gen_mode           VARCHAR(10) DEFAULT 'FAST'; -- FAST/DEEP
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS clarify_questions  TEXT; -- JSON [{q,type,options[],required}]
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS clarify_answers    TEXT; -- JSON [{q,a}]
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS research_plan       TEXT; -- JSON {keyQuestions[],dataNeeds[],hypotheses[],toolHints[]}
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS research_notes      TEXT; -- JSON [{agentId,question,status,facts[],gaps[]}]
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS fact_sheet          TEXT; -- JSON {entries[{key,value,source,confidence}],gaps[],warnings[]}

-- R3 增量迁移(知识库引用明细):随 brief/version 落库,前端简报/版本页展示「AI 引用了哪些知识」
ALTER TABLE sparkora_article_brief   ADD COLUMN IF NOT EXISTS rag_citations TEXT; -- JSON [{source,modelName,chunkType,score,chunkText}]

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
    rag_status         VARCHAR(20), -- S6.1:知识库检索状态 OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE
    word_count         INTEGER,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_version_project ON sparkora_article_version(project_id);

ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS rag_status VARCHAR(20);
-- S9 增量迁移:数值回查结果随版本落库(深度模式;快速模式为空)
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS fact_risks TEXT;
-- R3 增量迁移:知识库引用明细(与 rag_status 同源检索的命中块,JSON [{source,modelName,chunkType,score,chunkText}])
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS rag_citations TEXT;

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

-- 配图资产表（S3b：图库上传 / 文生图 / 图生图 三来源统一入库；S6 起图片入库即直接转存图床，本地不留）
CREATE TABLE IF NOT EXISTS sparkora_image_asset (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT,                         -- 关联项目（可空 = 全局图库；MVP 单 workspace 不单设 workspace_id）
    file_name      VARCHAR(255) NOT NULL,         -- 原始文件名（生成图为 prompt 摘要命名）
    source         VARCHAR(20)  NOT NULL,         -- upload / ai-text2img / ai-img2img / byd
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
-- S6 起该列迁移为 storage_key(见下方);本行仅为老库补列,S6 段会删除它,重复执行不残留
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS qiniu_key VARCHAR(300);

-- S6:图库完全依赖图床,本地不留。storage_path 移除;qiniu_key 语义通用化为 storage_key。
-- 历史已存本地的图不迁移(用户明确接受);已有 qiniu_key(已转存七牛)的记录把 key 搬进 storage_key 保留。
-- 注意:不能用 DO $$ 块——Spring ScriptUtils 不支持 dollar-quote(会把块按 ; 截断)。
-- 全部用单条幂等语句:补列 → 搬数据(只搬 storage_key 为空的)→ 删旧列。
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS storage_key VARCHAR(300);
UPDATE sparkora_image_asset SET storage_key = qiniu_key WHERE storage_key IS NULL AND qiniu_key IS NOT NULL;
ALTER TABLE sparkora_image_asset DROP COLUMN IF EXISTS qiniu_key;
ALTER TABLE sparkora_image_asset DROP COLUMN IF EXISTS storage_path;

-- S5:公众号发布留痕(草稿箱 media_id / 发布主题 / 时间 / 最近一次失败原因)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS publish_media_id VARCHAR(128);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS publish_theme VARCHAR(64);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS last_publish_error VARCHAR(1000);

-- S6:创作项目关联车型(可选;生成 brief/版本时注入车型知识库 RAG 上下文)
-- 多车型关联:改用关联表 sparkora_article_project_car,删除单值 car_model_id 字段
-- (关联表定义见下方 S6 车型知识库区块,因外键引用 sparkora_car_model 需在其后)
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS car_model_id;

-- S6:补充信息(可选;用户个人见解/独家资讯等,生成 brief/版本时注入 prompt 作为创作素材)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS extra_info TEXT;

-- S6:简报阶段选定的标题(可选;用户从标题候选中点选,生成版本时作为标题偏好注入 prompt)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS selected_title VARCHAR(200);

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

-- S6:创作项目-车型关联表(一篇文章可关联多个车型;生成时跨车型检索知识库)
-- 外键引用 sparkora_car_model,故定义在车型知识库区块末尾
CREATE TABLE IF NOT EXISTS sparkora_article_project_car (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES sparkora_article_project(id),
    car_model_id BIGINT       NOT NULL REFERENCES sparkora_car_model(id),
    sort_order   INTEGER      DEFAULT 0,           -- 关联顺序(首个为主车型)
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_project_car_project ON sparkora_article_project_car(project_id);
CREATE INDEX IF NOT EXISTS idx_project_car_model ON sparkora_article_project_car(car_model_id);

-- ============================================================================
-- S7:通用汽车知识库(车型库泛化;kb-generalize 任务)
-- 与车型域(sparkora_car_doc)并行:手工知识条目 → 切块 → 向量,支撑非车型对比主题检索。
-- 设计:doc/chunk/embedding 三表,以 doc 为根,无 model_id 约束;chunk 统一类型 KB_CHUNK。
-- ============================================================================
CREATE TABLE IF NOT EXISTS sparkora_kb_doc (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,              -- 知识标题(如「家用充电桩选择要点」)
    domain      VARCHAR(50)  NOT NULL DEFAULT '通用',-- 领域标签: 通用/充电/保养/政策/技术科普…
    content     TEXT         NOT NULL,              -- 原始正文(纯文本/Markdown)
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE, -- 停用后重建向量时跳过(检索层无特判)
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sparkora_kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL REFERENCES sparkora_kb_doc(id),
    seq         INT          NOT NULL,              -- 块序号(同 doc 内连续)
    chunk_text  TEXT         NOT NULL,              -- 首行固定「知识:<title>(<domain>)」
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc ON sparkora_kb_chunk(doc_id);

CREATE TABLE IF NOT EXISTS sparkora_kb_chunk_embedding (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    BIGINT       NOT NULL REFERENCES sparkora_kb_chunk(id),
    embedding   vector(1024) NOT NULL,              -- Qwen3-Embedding-8B,与 car_doc_embedding 同维
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_emb_vec ON sparkora_kb_chunk_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ============================================================================
-- S10:图库系统性重构(检索组织/性能成本/AI 生成体验)
-- 补列:内容哈希(sha256 hex,64 字符,入库去重;存量 NULL 允许,仅新增入库必填)
--      + 生成留档(实际命中模型 / 请求尺寸;size 原样存,auto 存 NULL)。
-- 存量哈希不回填(明确 out of scope);新列可空,旧代码兼容。
-- 注意:不能用 DO $$ 块——Spring ScriptUtils 不支持 dollar-quote,全部单条幂等语句。
-- ============================================================================
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS gen_model VARCHAR(100);
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS gen_size VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_image_asset_hash ON sparkora_image_asset(content_hash);
CREATE INDEX IF NOT EXISTS idx_image_asset_source ON sparkora_image_asset(source);

-- ============================================================================
-- 简报生成重设计(09-09-brief-gen-redesign):系统级检索设置,单行表。
-- 页面控制内部知识库/外部搜索的启用;运行时读取(SettingService 内存缓存),
-- 非 .env 部署级配置。kb 默认停用(用户决策:知识库存疑,暂停引用),
-- web_search 默认启用(外部搜索优先)。幂等建表。
-- ============================================================================
CREATE TABLE IF NOT EXISTS sparkora_setting (
    id                 BIGSERIAL PRIMARY KEY,
    kb_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,   -- 内部知识库启用(默认停用)
    web_search_enabled BOOLEAN      NOT NULL DEFAULT TRUE,   -- 外部搜索启用
    updated_by         BIGINT,                                -- 最近修改人用户 id
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT     NOT NULL DEFAULT 0       -- 逻辑删除(全局配置惯例:SMALLINT)
);

-- ============================================================================
-- 文章仿写(09-09-article-imitation):项目级新模式 genSource=IMITATION。
-- 原文/分析随 project 落库(1:1,不单设表);风格推荐随 brief 落库;
-- 相似度自检结果随 version 落库。全部幂等 ADD COLUMN,回滚仅需代码回退。
-- ============================================================================
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS gen_source          VARCHAR(20) NOT NULL DEFAULT 'TOPIC'; -- 创作来源:TOPIC/IMITATION
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS imitation_text     TEXT;                 -- 参考原文全文(仅 IMITATION 非空)
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS imitation_analysis TEXT;                 -- 原文分析结果 JSON(展示冗余存储)
ALTER TABLE sparkora_article_brief   ADD COLUMN IF NOT EXISTS style_recommendations TEXT;              -- 风格推荐 JSON [{styleId,name,reason,matchScore}]
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS similarity_score   DOUBLE PRECISION;     -- 与原文 5-gram 重合率 0~1(仅仿写版有值)
ALTER TABLE sparkora_article_version ADD COLUMN IF NOT EXISTS similarity_report  TEXT;                 -- 自检明细 JSON {maxRunLength,repeatedRuns:[{text,length}]}

-- ============================================================================
-- 09-10-versions-page-fix:深度链路历史版本回填 version_label/style_tag/word_count/title
-- 深度链路(DeepWriterService)自 S9 起漏填这 4 字段,存量行全 NULL。
-- 全部单条幂等语句(只处理 NULL/空行,重复执行无副作用);不能用 DO $$ 块(Spring ScriptUtils 不支持 dollar-quote)。
-- ============================================================================
UPDATE sparkora_article_version SET style_tag = '深度' WHERE style_tag IS NULL;
UPDATE sparkora_article_version SET word_count = length(content_md) WHERE word_count IS NULL AND content_md IS NOT NULL;
-- title:首行为「# 标题」时剥掉 Markdown 前缀(与代码 extractH1 语义一致),首行非 H1 时原样回退 topic
UPDATE sparkora_article_version v SET title = left(coalesce(nullif(regexp_replace(split_part(v.content_md, E'\n', 1), '^#\s+', ''), ''), p.topic), 200)
  FROM sparkora_article_project p
 WHERE v.project_id = p.id AND coalesce(v.title, '') = '';
-- version_label:仅补 NULL 行,按项目内创建序(row_number)映射 A/B/C…(同 VersionService 编号口径);超 8 个回退 'A'
UPDATE sparkora_article_version v SET version_label = sub.lbl
 FROM (
   SELECT id, CASE rn WHEN 1 THEN 'A' WHEN 2 THEN 'B' WHEN 3 THEN 'C' WHEN 4 THEN 'D'
            WHEN 5 THEN 'E' WHEN 6 THEN 'F' WHEN 7 THEN 'G' WHEN 8 THEN 'H' ELSE 'A' END AS lbl
   FROM (SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY id) AS rn
         FROM sparkora_article_version WHERE version_label IS NULL) t
 ) sub
 WHERE v.id = sub.id;
-- 项目状态回填:历史深度生成不推状态机,存量「有版本但仍 READY/DRAFT」的项目推到 VERSIONS_READY,
-- current_version_id 为空时默认指向首版(同 VersionService「默认选第一版」语义);幂等(执行后无 READY/DRAFT-with-versions 行)。
UPDATE sparkora_article_project p
   SET status = 'VERSIONS_READY',
       current_version_id = COALESCE(p.current_version_id,
                                     (SELECT min(v.id) FROM sparkora_article_version v WHERE v.project_id = p.id)),
       updated_at = CURRENT_TIMESTAMP
 WHERE p.deleted = 0
   AND p.status IN ('READY', 'DRAFT')
   AND EXISTS (SELECT 1 FROM sparkora_article_version v WHERE v.project_id = p.id);

-- ============================================================================
-- 09-11-brief-gen-flow-refactor:深度研究计划(clarify)异步化。
-- sparkora_article_brief 增 plan_status(DEEP: PLANNING/READY;其余 null),
-- 作为「计划生成中」的可查询 brief 侧态;部分唯一索引保证同一项目同时至多一条 PLANNING,
-- 是双击/双开触发的数据库级并发兜底(应用层撞索引转 409)。
-- 全部单条幂等语句;不能用 DO $$ 块(Spring ScriptUtils 不支持 dollar-quote)。
-- ============================================================================
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS plan_status VARCHAR(20); -- DEEP: PLANNING/READY;其余 null
CREATE UNIQUE INDEX IF NOT EXISTS uq_brief_planning
    ON sparkora_article_brief(project_id) WHERE plan_status = 'PLANNING';
