-- Password reuse protection: keep only the newest hashes per user.
CREATE TABLE IF NOT EXISTS king_ai_password_history (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_password_history_user (user_id, id),
    CONSTRAINT fk_password_history_user FOREIGN KEY (user_id) REFERENCES king_ai_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='密码历史（用于禁止复用）';
