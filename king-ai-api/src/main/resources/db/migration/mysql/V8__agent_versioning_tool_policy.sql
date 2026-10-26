-- Immutable published snapshots for agents.
CREATE TABLE IF NOT EXISTS king_ai_agent_version (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    agent_id    BIGINT       NOT NULL,
    space_id    BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    config_json LONGTEXT     NOT NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_version (agent_id, version_no),
    KEY idx_agent_version_space (space_id),
    CONSTRAINT fk_agent_version_agent FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体发布快照';

-- Tool policy and fallback model stored with the agent configuration.
ALTER TABLE king_ai_agent ADD COLUMN allowed_tools VARCHAR(4000) NULL COMMENT '允许调用的工具白名单（JSON 数组，空表示不限制）';
ALTER TABLE king_ai_agent ADD COLUMN tool_approval_mode VARCHAR(16) NOT NULL DEFAULT 'WRITE' COMMENT '工具审批模式：NONE/WRITE/ALL';
ALTER TABLE king_ai_agent ADD COLUMN fallback_model_id BIGINT NULL COMMENT '故障降级模型';

-- Per-document retrieval ACL. NULL/empty means visible to the whole space.
ALTER TABLE king_ai_knowledge_doc ADD COLUMN allowed_roles VARCHAR(100) NULL COMMENT '可检索角色，逗号分隔：VIEWER,EDITOR,ADMIN,OWNER';
