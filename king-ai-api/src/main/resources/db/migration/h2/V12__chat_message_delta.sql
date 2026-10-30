-- Streaming assistant output is appended as deltas so the message row is not rewritten on every flush.
CREATE TABLE IF NOT EXISTS king_ai_chat_message_delta (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    message_id      BIGINT      NOT NULL,
    content_delta   MEDIUMTEXT  NULL,
    reasoning_delta MEDIUMTEXT  NULL,
    tool_trace      MEDIUMTEXT  NULL,
    citations       MEDIUMTEXT  NULL,
    created_at      DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_message_delta_message ON king_ai_chat_message_delta (message_id);
ALTER TABLE king_ai_chat_message_delta ADD CONSTRAINT fk_message_delta_message
    FOREIGN KEY (message_id) REFERENCES king_ai_chat_message (id) ON DELETE CASCADE;
