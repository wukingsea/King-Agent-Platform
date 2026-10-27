ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS last_login_at DATETIME NULL;
ALTER TABLE king_ai_supplier ADD COLUMN IF NOT EXISTS api_key_rotated_at DATETIME NULL;

CREATE TABLE IF NOT EXISTS king_ai_access_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    space_id     BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    summary      VARCHAR(1000) NULL,
    generated_by BIGINT      NULL,
    generated_at DATETIME    NOT NULL,
    completed_at DATETIME    NULL,
    version      INT         NOT NULL DEFAULT 0,
    created_by   BIGINT      NULL,
    created_at   DATETIME    NOT NULL,
    updated_by   BIGINT      NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_access_review_space
    ON king_ai_access_review (space_id, status);
ALTER TABLE king_ai_access_review ADD CONSTRAINT fk_access_review_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS king_ai_access_review_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    review_id        BIGINT       NOT NULL,
    space_id         BIGINT       NOT NULL,
    subject_type     VARCHAR(20)  NOT NULL,
    subject_id       BIGINT       NOT NULL,
    subject_name     VARCHAR(200) NOT NULL,
    role             VARCHAR(20)  NULL,
    last_activity_at DATETIME     NULL,
    flags            VARCHAR(500) NULL,
    decision         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by       BIGINT       NULL,
    decided_at       DATETIME     NULL,
    note             VARCHAR(500) NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_access_item_review
    ON king_ai_access_review_item (review_id, decision);
ALTER TABLE king_ai_access_review_item ADD CONSTRAINT fk_access_item_review
    FOREIGN KEY (review_id) REFERENCES king_ai_access_review (id) ON DELETE CASCADE;
