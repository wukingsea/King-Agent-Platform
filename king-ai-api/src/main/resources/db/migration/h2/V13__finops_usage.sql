-- FinOps: per-space monthly token budget and an append-only usage ledger.
ALTER TABLE king_ai_space ADD COLUMN IF NOT EXISTS monthly_token_quota BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS king_ai_usage_ledger (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    space_id          BIGINT      NOT NULL,
    agent_id          BIGINT      NOT NULL,
    session_id        BIGINT      NULL,
    message_id        BIGINT      NULL,
    model             VARCHAR(150) NULL,
    source_type       VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    cost_micros       BIGINT      NOT NULL DEFAULT 0,
    currency          VARCHAR(8)  NOT NULL DEFAULT 'USD',
    status            VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at        DATETIME    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_usage_space_created ON king_ai_usage_ledger (space_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_agent_created ON king_ai_usage_ledger (agent_id, created_at);
