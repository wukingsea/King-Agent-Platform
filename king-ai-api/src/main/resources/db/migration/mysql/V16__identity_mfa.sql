-- Multi-factor authentication and external identity bindings.
ALTER TABLE king_ai_user ADD COLUMN mfa_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 TOTP 二次验证';
ALTER TABLE king_ai_user ADD COLUMN mfa_secret VARCHAR(1000) NULL COMMENT 'TOTP 密钥（加密）';
ALTER TABLE king_ai_user ADD COLUMN mfa_recovery_codes VARCHAR(4000) NULL COMMENT '恢复码 SHA-256 哈希 JSON（加密）';
ALTER TABLE king_ai_user ADD COLUMN mfa_enrolled_at DATETIME NULL COMMENT 'MFA 启用时间';

-- One local user may be linked to several IdPs. (provider, subject) is globally unique.
CREATE TABLE IF NOT EXISTS king_ai_user_identity (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(40)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(200) NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identity (provider, subject),
    KEY idx_user_identity_user (user_id),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES king_ai_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部身份绑定';
