-- Multi-factor authentication and external identity bindings.
ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS mfa_enabled TINYINT NOT NULL DEFAULT 0;
ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(1000) NULL;
ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS mfa_recovery_codes VARCHAR(4000) NULL;
ALTER TABLE king_ai_user ADD COLUMN IF NOT EXISTS mfa_enrolled_at DATETIME NULL;

-- One local user may be linked to several IdPs. (provider, subject) is globally unique.
CREATE TABLE IF NOT EXISTS king_ai_user_identity (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(40)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(200) NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_identity ON king_ai_user_identity (provider, subject);
CREATE INDEX IF NOT EXISTS idx_user_identity_user ON king_ai_user_identity (user_id);
ALTER TABLE king_ai_user_identity ADD CONSTRAINT fk_user_identity_user
    FOREIGN KEY (user_id) REFERENCES king_ai_user (id) ON DELETE CASCADE;
