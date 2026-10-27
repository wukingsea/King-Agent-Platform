CREATE TABLE IF NOT EXISTS king_ai_compliance_setting (
    id                        BIGINT      NOT NULL AUTO_INCREMENT,
    space_id                  BIGINT      NOT NULL,
    pii_enabled               TINYINT     NOT NULL DEFAULT 1,
    moderation_mode           VARCHAR(16) NOT NULL DEFAULT 'OFF',
    moderation_input_action   VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    moderation_output_action  VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    blocklist                 TEXT        NULL,
    pii_custom_patterns       TEXT        NULL,
    version                   INT         NOT NULL DEFAULT 0,
    created_by                BIGINT      NULL,
    created_at                DATETIME    NOT NULL,
    updated_by                BIGINT      NULL,
    updated_at                DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_compliance_setting_space
    ON king_ai_compliance_setting (space_id);
ALTER TABLE king_ai_compliance_setting ADD CONSTRAINT fk_compliance_setting_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
