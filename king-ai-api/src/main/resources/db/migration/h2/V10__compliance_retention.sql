CREATE TABLE IF NOT EXISTS king_ai_retention_policy (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    space_id              BIGINT      NOT NULL,
    resource_type         VARCHAR(20) NOT NULL,
    retention_days        INT         NOT NULL,
    enabled               TINYINT     NOT NULL DEFAULT 1,
    archive_before_delete TINYINT     NOT NULL DEFAULT 1,
    version               INT         NOT NULL DEFAULT 0,
    created_by            BIGINT      NULL,
    created_at            DATETIME    NOT NULL,
    updated_by            BIGINT      NULL,
    updated_at            DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_retention_space_type
    ON king_ai_retention_policy (space_id, resource_type);
ALTER TABLE king_ai_retention_policy ADD CONSTRAINT fk_retention_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_legal_hold (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    resource_type VARCHAR(20)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    until         DATETIME     NULL,
    released_at   DATETIME     NULL,
    released_by   BIGINT       NULL,
    version       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL,
    updated_by    BIGINT       NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_hold_space_type
    ON king_ai_legal_hold (space_id, resource_type, released_at);
CREATE INDEX IF NOT EXISTS idx_hold_resource
    ON king_ai_legal_hold (space_id, resource_id);
ALTER TABLE king_ai_legal_hold ADD CONSTRAINT fk_hold_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_export_job (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    export_type   VARCHAR(20)   NOT NULL,
    format        VARCHAR(10)   NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    from_at       DATETIME      NULL,
    to_at         DATETIME      NULL,
    file_name     VARCHAR(255)  NULL,
    file_path     VARCHAR(1000) NULL,
    row_count     BIGINT        NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    requested_by  BIGINT        NULL,
    expires_at    DATETIME      NULL,
    started_at    DATETIME      NULL,
    completed_at  DATETIME      NULL,
    version       INT           NOT NULL DEFAULT 0,
    created_by    BIGINT        NULL,
    created_at    DATETIME      NOT NULL,
    updated_by    BIGINT        NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_export_space_status
    ON king_ai_export_job (space_id, status);
CREATE INDEX IF NOT EXISTS idx_export_expires
    ON king_ai_export_job (expires_at);
ALTER TABLE king_ai_export_job ADD CONSTRAINT fk_export_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
