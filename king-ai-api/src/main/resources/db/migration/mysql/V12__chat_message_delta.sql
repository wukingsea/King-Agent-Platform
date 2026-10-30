-- Streaming assistant output is appended as deltas so the message row is not rewritten on every flush.
CREATE TABLE IF NOT EXISTS king_ai_chat_message_delta (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    message_id      BIGINT      NOT NULL COMMENT '消息 ID',
    content_delta   MEDIUMTEXT  NULL COMMENT '增量正文',
    reasoning_delta MEDIUMTEXT  NULL COMMENT '增量推理',
    tool_trace      MEDIUMTEXT  NULL COMMENT '工具轨迹快照',
    citations       MEDIUMTEXT  NULL COMMENT '引用快照',
    created_at      DATETIME    NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_message_delta_message FOREIGN KEY (message_id)
        REFERENCES king_ai_chat_message (id) ON DELETE CASCADE
);
CREATE INDEX idx_message_delta_message ON king_ai_chat_message_delta (message_id);
