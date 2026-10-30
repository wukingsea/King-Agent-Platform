-- Release governance on top of immutable agent version snapshots.
-- Legacy rows were published directly, so they start as PUBLISHED.
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS requested_by BIGINT NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS requested_at DATETIME NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS approved_by BIGINT NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS approved_at DATETIME NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS review_comment VARCHAR(1000) NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS canary_percent INT NOT NULL DEFAULT 0;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS published_at DATETIME NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS eval_total INT NOT NULL DEFAULT 0;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS eval_passed INT NOT NULL DEFAULT 0;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS eval_score DECIMAL(5,4) NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS eval_summary VARCHAR(2000) NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS eval_run_at DATETIME NULL;
ALTER TABLE king_ai_agent_version ADD COLUMN IF NOT EXISTS config_hash VARCHAR(64) NULL;
CREATE INDEX IF NOT EXISTS idx_agent_version_status ON king_ai_agent_version (agent_id, status);

-- Evaluation cases used as an approval gate before a version can be released.
CREATE TABLE IF NOT EXISTS king_ai_agent_eval_case (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    space_id          BIGINT       NOT NULL,
    agent_id          BIGINT       NOT NULL,
    name              VARCHAR(120) NOT NULL,
    prompt            TEXT         NOT NULL,
    expected_keywords VARCHAR(2000) NOT NULL,
    weight            INT          NOT NULL DEFAULT 1,
    status            TINYINT      NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_eval_case_agent ON king_ai_agent_eval_case (agent_id, status);
CREATE INDEX IF NOT EXISTS idx_eval_case_space ON king_ai_agent_eval_case (space_id);
ALTER TABLE king_ai_agent_eval_case ADD CONSTRAINT fk_eval_case_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;
