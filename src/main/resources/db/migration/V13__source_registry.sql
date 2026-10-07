-- 10-05-source-crawl-base B: 信源采集基座(信源注册表 + 一源多栏目 + 采集任务表)。
-- 复用 sparkora_news/_doc/_embedding 的共享 id 空间(domain=NEWS 向量 refId=news_doc.id,见父 design §2.1/§2.5.1);
-- 通用信源采集产物写入 sparkora_news(source_id 可空,存量 BYD 行 NULL) + sparkora_news_doc,不新建平行 id 空间。
-- 全部单条幂等语句;不能用 DO $$ 块(Spring ScriptUtils 不支持 dollar-quote)。

-- 信源注册表(站点/机构级,源级公共字段 + 排期)
CREATE TABLE IF NOT EXISTS sparkora_source (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,                    -- 源名(工信部/乘联会/盖世/...)
    type             VARCHAR(10)  NOT NULL DEFAULT 'SITE',     -- RSS | SITE
    vertical         VARCHAR(30),                              -- 汽车/政策/...(预留)
    cron             VARCHAR(50),                              -- 每源 cron(动态注册,源级默认)
    window_start_day SMALLINT,                                 -- 发布窗口起始日(可选)
    window_end_day   SMALLINT,                                 -- 发布窗口结束日(可选;窗口内每日触发)
    authority_tier   VARCHAR(20),                              -- official|industry|media|ugc(F 用;默认不启用分档)
    need_crawl4ai    BOOLEAN      NOT NULL DEFAULT FALSE,      -- 源级默认是否需 Crawl4AI(可被栏目覆盖)
    enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_batch_key   VARCHAR(30),                              -- 发布窗口本批完成标记(sourceId+年月,成功后写入)
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_source_enabled ON sparkora_source(enabled);

-- 栏目表(一个源 ≥1 个列表页/feed;列表地址/分类/选择器下沉到栏目级)
CREATE TABLE IF NOT EXISTS sparkora_source_channel (
    id              BIGSERIAL PRIMARY KEY,
    source_id       BIGINT       NOT NULL REFERENCES sparkora_source(id),
    name            VARCHAR(100) NOT NULL,                     -- 栏目名(乘联会「车市解读」)
    list_url        VARCHAR(500) NOT NULL,                     -- 该栏目列表/feed 地址
    detail_base_url VARCHAR(500),                              -- 详情相对链接基址(相对图链同样按此解析)
    category        VARCHAR(30),                               -- 官方新闻|销量数据|投诉榜|政策公示|行业资讯
    parse_rules     TEXT,                                      -- JSON:列表/详情/日期/正文/表格/图片选择器
    need_crawl4ai   BOOLEAN,                                   -- NULL=继承源级
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_source_channel_source ON sparkora_source_channel(source_id);

-- 采集任务表(仿 sparkora_news_sync_job;单源任务,channel_id 可空=整源)
CREATE TABLE IF NOT EXISTS sparkora_source_job (
    id           BIGSERIAL PRIMARY KEY,
    source_id    BIGINT       NOT NULL REFERENCES sparkora_source(id),
    channel_id   BIGINT       REFERENCES sparkora_source_channel(id),
    job_type     VARCHAR(20)  NOT NULL,                        -- SCHEDULED|MANUAL|RETRY
    status       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',      -- RUNNING|SUCCESS|PARTIAL|FAILED
    batch_key    VARCHAR(30),                                  -- 发布窗口批次键(成功且无失败时回写 source.last_batch_key)
    total        INTEGER      DEFAULT 0,
    success      INTEGER      DEFAULT 0,
    failed       INTEGER      DEFAULT 0,
    degraded     INTEGER      DEFAULT 0,                       -- 降级跳过数(Crawl4AI 未就绪等)
    failed_items TEXT,                                         -- JSON:[{channelId,externalId,title,error}]
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    error_msg    VARCHAR(1000),
    created_by   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_source_job_source ON sparkora_source_job(source_id);
CREATE INDEX IF NOT EXISTS idx_source_job_created ON sparkora_source_job(created_at);

-- 复用 sparkora_news 承载通用信源采集产物(source_id 可空=存量 BYD;channel_id/category 供查询筛选)
ALTER TABLE sparkora_news ADD COLUMN IF NOT EXISTS source_id  BIGINT REFERENCES sparkora_source(id);
ALTER TABLE sparkora_news ADD COLUMN IF NOT EXISTS channel_id BIGINT REFERENCES sparkora_source_channel(id);
ALTER TABLE sparkora_news ADD COLUMN IF NOT EXISTS category   VARCHAR(30);
CREATE INDEX IF NOT EXISTS idx_news_source ON sparkora_news(source_id);
