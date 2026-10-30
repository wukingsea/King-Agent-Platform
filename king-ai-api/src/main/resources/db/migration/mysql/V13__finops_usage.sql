-- FinOps: per-space monthly token budget and an append-only usage ledger.
ALTER TABLE king_ai_space
    ADD COLUMN monthly_token_quota BIGINT NOT NULL DEFAULT 0 COMMENT '每月 Token 配额，0 表示不限额';

CREATE TABLE IF NOT EXISTS king_ai_usage_ledger (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    space_id          BIGINT       NOT NULL COMMENT '空间 ID',
    agent_id          BIGINT       NOT NULL COMMENT '智能体 ID',
    session_id        BIGINT       NULL COMMENT '会话 ID',
    message_id        BIGINT       NULL COMMENT '助手消息 ID',
    model             VARCHAR(150) NULL COMMENT '实际服务的模型标识',
    source_type       VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN/PUBLIC',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 Token',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 Token',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '总 Token',
    cost_micros       BIGINT       NOT NULL DEFAULT 0 COMMENT '估算成本（百万分之一货币单位）',
    currency          VARCHAR(8)   NOT NULL DEFAULT 'USD' COMMENT '币种',
    status            VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED' COMMENT 'COMPLETED/STOPPED/FAILED',
    created_at        DATETIME     NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_usage_space_created (space_id, created_at),
    KEY idx_usage_agent_created (agent_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 用量账本';
