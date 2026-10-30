-- Archive spaces and agents instead of removing them: chat history, audit trail,
-- usage ledger and agent versions reference these rows and must survive deletion.
ALTER TABLE king_ai_space
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间，NULL 表示未删除';
ALTER TABLE king_ai_agent
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间，NULL 表示未删除';
CREATE INDEX idx_agent_space_deleted ON king_ai_agent (space_id, deleted_at);

-- Archived agents keep their model reference, so model deletion must detach instead
-- of failing on the foreign key.
ALTER TABLE king_ai_agent DROP FOREIGN KEY fk_agent_model;
ALTER TABLE king_ai_agent MODIFY COLUMN model_id BIGINT NULL;
ALTER TABLE king_ai_agent ADD CONSTRAINT fk_agent_model
    FOREIGN KEY (model_id) REFERENCES king_ai_supplier_model (id) ON DELETE SET NULL;
