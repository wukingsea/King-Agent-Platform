-- Archive spaces and agents instead of removing them: chat history, audit trail,
-- usage ledger and agent versions reference these rows and must survive deletion.
ALTER TABLE king_ai_space ADD COLUMN IF NOT EXISTS deleted_at DATETIME NULL;
ALTER TABLE king_ai_agent ADD COLUMN IF NOT EXISTS deleted_at DATETIME NULL;
CREATE INDEX IF NOT EXISTS idx_agent_space_deleted ON king_ai_agent (space_id, deleted_at);

-- Archived agents keep their model reference, so model deletion must detach instead
-- of failing on the foreign key.
ALTER TABLE king_ai_agent ALTER COLUMN model_id SET NULL;
ALTER TABLE king_ai_agent DROP CONSTRAINT IF EXISTS fk_agent_model;
ALTER TABLE king_ai_agent ADD CONSTRAINT fk_agent_model
    FOREIGN KEY (model_id) REFERENCES king_ai_supplier_model (id) ON DELETE SET NULL;
