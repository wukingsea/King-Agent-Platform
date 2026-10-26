CREATE TABLE IF NOT EXISTS king_ai_knowledge_index_job (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts    INT           NOT NULL DEFAULT 0,
    next_run_at DATETIME      NOT NULL,
    last_error  VARCHAR(1000) NULL,
    created_at  DATETIME      NOT NULL,
    updated_at  DATETIME      NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_index_job_doc ON king_ai_knowledge_index_job (doc_id);
CREATE INDEX IF NOT EXISTS idx_index_job_due ON king_ai_knowledge_index_job (status, next_run_at);
