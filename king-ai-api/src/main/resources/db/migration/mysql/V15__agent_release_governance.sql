-- Release governance on top of immutable agent version snapshots.
-- Legacy rows were published directly, so they start as PUBLISHED.
ALTER TABLE king_ai_agent_version ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT '发布状态：DRAFT/PENDING_REVIEW/APPROVED/CANARY/PUBLISHED/REJECTED/ARCHIVED';
ALTER TABLE king_ai_agent_version ADD COLUMN requested_by BIGINT NULL COMMENT '提交人';
ALTER TABLE king_ai_agent_version ADD COLUMN requested_at DATETIME NULL COMMENT '提交时间';
ALTER TABLE king_ai_agent_version ADD COLUMN approved_by BIGINT NULL COMMENT '审批人';
ALTER TABLE king_ai_agent_version ADD COLUMN approved_at DATETIME NULL COMMENT '审批时间';
ALTER TABLE king_ai_agent_version ADD COLUMN review_comment VARCHAR(1000) NULL COMMENT '审批意见';
ALTER TABLE king_ai_agent_version ADD COLUMN canary_percent INT NOT NULL DEFAULT 0 COMMENT '灰度流量百分比';
ALTER TABLE king_ai_agent_version ADD COLUMN published_at DATETIME NULL COMMENT '正式发布时间';
ALTER TABLE king_ai_agent_version ADD COLUMN eval_total INT NOT NULL DEFAULT 0 COMMENT '评测用例总数';
ALTER TABLE king_ai_agent_version ADD COLUMN eval_passed INT NOT NULL DEFAULT 0 COMMENT '评测通过数';
ALTER TABLE king_ai_agent_version ADD COLUMN eval_score DECIMAL(5,4) NULL COMMENT '加权评测得分 0-1';
ALTER TABLE king_ai_agent_version ADD COLUMN eval_summary VARCHAR(2000) NULL COMMENT '评测摘要';
ALTER TABLE king_ai_agent_version ADD COLUMN eval_run_at DATETIME NULL COMMENT '评测时间';
ALTER TABLE king_ai_agent_version ADD COLUMN config_hash VARCHAR(64) NULL COMMENT '快照 SHA-256';
ALTER TABLE king_ai_agent_version ADD KEY idx_agent_version_status (agent_id, status);

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
    PRIMARY KEY (id),
    KEY idx_eval_case_agent (agent_id, status),
    KEY idx_eval_case_space (space_id),
    CONSTRAINT fk_eval_case_agent FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体评测用例';
