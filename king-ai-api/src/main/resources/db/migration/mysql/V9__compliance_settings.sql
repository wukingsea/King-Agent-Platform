CREATE TABLE IF NOT EXISTS king_ai_compliance_setting (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    space_id                  BIGINT      NOT NULL,
    pii_enabled               TINYINT     NOT NULL DEFAULT 1 COMMENT '是否开启 PII 脱敏',
    moderation_mode           VARCHAR(16) NOT NULL DEFAULT 'OFF' COMMENT 'OFF/LOCAL/EXTERNAL/HYBRID',
    moderation_input_action   VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/WARN',
    moderation_output_action  VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK/WARN',
    blocklist                 TEXT        NULL COMMENT '违禁词，换行分隔',
    pii_custom_patterns       TEXT        NULL COMMENT '自定义 PII 正则，换行分隔',
    version                   INT         NOT NULL DEFAULT 0,
    created_by                BIGINT      NULL,
    created_at                DATETIME    NOT NULL,
    updated_by                BIGINT      NULL,
    updated_at                DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_compliance_setting_space (space_id),
    CONSTRAINT fk_compliance_setting_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间内容合规配置';
