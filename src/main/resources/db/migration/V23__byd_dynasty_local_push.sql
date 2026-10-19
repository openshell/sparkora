-- 10-16-byd-dynasty-local-push: BYD 王朝小程序本地推送任务契约。
-- Sparkora Server 只保存任务与审计状态，不承载本机 MMKV 检测/备份/写入逻辑。
-- 幂等：使用 CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS sparkora_byd_dynasty_agent (
    id                   BIGSERIAL PRIMARY KEY,
    owner_user_id        BIGINT       NOT NULL,
    agent_id             VARCHAR(64)  NOT NULL,
    machine_name         VARCHAR(255),
    os_name              VARCHAR(100),
    agent_version        VARCHAR(64),
    status               VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',  -- ONLINE / OFFLINE
    last_seen_at         TIMESTAMP,
    last_preflight_json  TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_byd_agent_agent_id
    ON sparkora_byd_dynasty_agent(agent_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_byd_agent_owner
    ON sparkora_byd_dynasty_agent(owner_user_id, deleted);
CREATE INDEX IF NOT EXISTS idx_byd_agent_last_seen
    ON sparkora_byd_dynasty_agent(status, last_seen_at);

CREATE TABLE IF NOT EXISTS sparkora_byd_dynasty_push_job (
    id              BIGSERIAL PRIMARY KEY,
    job_id          VARCHAR(64)  NOT NULL,
    project_id      BIGINT       NOT NULL,
    version_id      BIGINT       NOT NULL,
    agent_id        BIGINT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payload_json    TEXT,
    result_json     TEXT,
    error_message   VARCHAR(1000),
    created_by      BIGINT       NOT NULL,
    claimed_at      TIMESTAMP,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_byd_push_job_job_id
    ON sparkora_byd_dynasty_push_job(job_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_byd_push_job_project
    ON sparkora_byd_dynasty_push_job(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_byd_push_job_claim
    ON sparkora_byd_dynasty_push_job(created_by, status, created_at ASC)
    WHERE status = 'PENDING' AND deleted = 0;

-- BYD 发布结果与公众号发布状态字段完全隔离。
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_published_at      TIMESTAMP;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_last_job_id       VARCHAR(64);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_last_draft_id     VARCHAR(64);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_last_draft_count  INTEGER;
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_club_id           VARCHAR(100);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_club_title        VARCHAR(255);
ALTER TABLE sparkora_article_project ADD COLUMN IF NOT EXISTS byd_dynasty_last_error        VARCHAR(1000);
