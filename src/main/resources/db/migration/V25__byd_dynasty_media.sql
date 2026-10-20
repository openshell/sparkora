-- 10-20-miniapp-media-push: BYD 小程序正文媒体暂存。
-- 文件本身位于 BYD_MEDIA_STORAGE_DIR，数据库只保存受控相对路径/URL 与元数据。
-- 幂等：使用 CREATE TABLE/INDEX IF NOT EXISTS；不得修改既有迁移。

CREATE TABLE IF NOT EXISTS sparkora_byd_dynasty_media (
    id                BIGSERIAL PRIMARY KEY,
    media_id          VARCHAR(64)  NOT NULL,
    project_id        BIGINT       NOT NULL,
    media_type        VARCHAR(10)  NOT NULL,
    source_kind       VARCHAR(10)  NOT NULL,
    storage_state     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    storage_path      VARCHAR(500),
    source_url        TEXT,
    file_name         VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    byte_size         BIGINT       NOT NULL DEFAULT 0,
    width             INTEGER,
    height            INTEGER,
    duration_seconds  NUMERIC(8,3),
    created_by        BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL,
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_byd_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_byd_media_source CHECK (source_kind IN ('STAGED', 'URL')),
    CONSTRAINT ck_byd_media_state CHECK (storage_state IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_byd_media_source_fields CHECK (
        (source_kind = 'STAGED' AND source_url IS NULL)
        OR (source_kind = 'URL' AND source_url IS NOT NULL AND storage_path IS NULL)
    ),
    CONSTRAINT ck_byd_media_size CHECK (byte_size >= 0),
    CONSTRAINT ck_byd_media_metadata CHECK (
        (media_type = 'IMAGE' AND width IS NOT NULL AND width > 0
            AND height IS NOT NULL AND height > 0 AND duration_seconds IS NULL)
        OR
        (media_type = 'VIDEO' AND width IS NOT NULL AND width > 0
            AND height IS NOT NULL AND height > 0
            AND duration_seconds IS NOT NULL AND duration_seconds >= 2 AND duration_seconds <= 120)
    ),
    CONSTRAINT ck_byd_media_ready_fields CHECK (
        storage_state <> 'READY'
        OR (source_kind = 'STAGED' AND storage_path IS NOT NULL AND byte_size > 0)
        OR (source_kind = 'URL' AND source_url IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_byd_media_media_id
    ON sparkora_byd_dynasty_media(media_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_byd_media_project
    ON sparkora_byd_dynasty_media(project_id, media_type, deleted);
CREATE INDEX IF NOT EXISTS idx_byd_media_expiry
    ON sparkora_byd_dynasty_media(expires_at, storage_state, deleted);
CREATE INDEX IF NOT EXISTS idx_byd_media_state
    ON sparkora_byd_dynasty_media(storage_state, created_at);

COMMENT ON TABLE sparkora_byd_dynasty_media IS '比亚迪王朝小程序正文媒体暂存；文件由 Agent 通过任务范围接口下载后上传 BYD CDN';
COMMENT ON COLUMN sparkora_byd_dynasty_media.storage_path IS '相对 BYD_MEDIA_STORAGE_DIR 的受控路径；不得暴露给前端';
COMMENT ON COLUMN sparkora_byd_dynasty_media.expires_at IS '暂存过期时间；创建推送任务时延长到任务保护窗口';
