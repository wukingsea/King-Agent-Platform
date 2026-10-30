-- Password reuse protection: keep only the newest hashes per user.
CREATE TABLE IF NOT EXISTS king_ai_password_history (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON king_ai_password_history (user_id, id);
ALTER TABLE king_ai_password_history ADD CONSTRAINT fk_password_history_user
    FOREIGN KEY (user_id) REFERENCES king_ai_user (id) ON DELETE CASCADE;
