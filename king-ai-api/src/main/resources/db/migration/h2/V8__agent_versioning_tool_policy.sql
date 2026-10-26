-- Immutable published snapshots for agents.
CREATE TABLE IF NOT EXISTS king_ai_agent_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id    BIGINT       NOT NULL,
    space_id    BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    config_json TEXT         NOT NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_version ON king_ai_agent_version (agent_id, version_no);
CREATE INDEX IF NOT EXISTS idx_agent_version_space ON king_ai_agent_version (space_id);
ALTER TABLE king_ai_agent_version ADD CONSTRAINT fk_agent_version_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;

-- Tool policy and fallback model stored with the agent configuration.
ALTER TABLE king_ai_agent ADD COLUMN IF NOT EXISTS allowed_tools VARCHAR(4000) NULL;
ALTER TABLE king_ai_agent ADD COLUMN IF NOT EXISTS tool_approval_mode VARCHAR(16) NOT NULL DEFAULT 'WRITE';
ALTER TABLE king_ai_agent ADD COLUMN IF NOT EXISTS fallback_model_id BIGINT NULL;

-- Per-document retrieval ACL. NULL/empty means visible to the whole space.
ALTER TABLE king_ai_knowledge_doc ADD COLUMN IF NOT EXISTS allowed_roles VARCHAR(100) NULL;
