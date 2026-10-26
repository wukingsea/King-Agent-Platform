ALTER TABLE king_ai_user
    ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0;

ALTER TABLE king_ai_user_space
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'VIEWER';

UPDATE king_ai_user_space
SET role = 'OWNER'
WHERE id IN (
    SELECT first_id FROM (
        SELECT MIN(id) AS first_id
        FROM king_ai_user_space
        GROUP BY space_id
    ) first_links
);

ALTER TABLE king_ai_chat_session
    ADD COLUMN owner_user_id BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_session_space_owner ON king_ai_chat_session (space_id, owner_user_id);
CREATE INDEX IF NOT EXISTS idx_session_owner ON king_ai_chat_session (owner_user_id);
