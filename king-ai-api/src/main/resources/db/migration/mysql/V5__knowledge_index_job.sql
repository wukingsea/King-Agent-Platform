CREATE TABLE IF NOT EXISTS king_ai_knowledge_index_job (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts    INT           NOT NULL DEFAULT 0,
    next_run_at DATETIME      NOT NULL,
    last_error  VARCHAR(1000) NULL,
    created_at  DATETIME      NOT NULL,
    updated_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_job_doc (doc_id),
    KEY idx_index_job_due (status, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库索引任务';
