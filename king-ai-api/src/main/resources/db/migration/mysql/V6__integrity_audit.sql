-- Remove referential garbage created before foreign keys existed.
DELETE FROM king_ai_chunk_vector WHERE doc_id NOT IN (SELECT id FROM king_ai_knowledge_doc);
DELETE FROM king_ai_knowledge_chunk WHERE doc_id NOT IN (SELECT id FROM king_ai_knowledge_doc);
DELETE FROM king_ai_knowledge_index_job WHERE doc_id NOT IN (SELECT id FROM king_ai_knowledge_doc);
DELETE FROM king_ai_chunk_vector WHERE doc_id IN (
    SELECT d.id FROM king_ai_knowledge_doc d
    WHERE d.knowledge_base_id NOT IN (SELECT id FROM king_ai_knowledge_base));
DELETE FROM king_ai_knowledge_chunk WHERE doc_id IN (
    SELECT d.id FROM king_ai_knowledge_doc d
    WHERE d.knowledge_base_id NOT IN (SELECT id FROM king_ai_knowledge_base));
DELETE FROM king_ai_knowledge_index_job WHERE doc_id IN (
    SELECT d.id FROM king_ai_knowledge_doc d
    WHERE d.knowledge_base_id NOT IN (SELECT id FROM king_ai_knowledge_base));
DELETE FROM king_ai_knowledge_doc WHERE knowledge_base_id NOT IN (SELECT id FROM king_ai_knowledge_base);
DELETE FROM king_ai_chat_message WHERE session_id NOT IN (SELECT id FROM king_ai_chat_session);
DELETE FROM king_ai_chat_session WHERE agent_id NOT IN (SELECT id FROM king_ai_agent);
DELETE FROM king_ai_chat_session WHERE space_id NOT IN (SELECT id FROM king_ai_space);
UPDATE king_ai_chat_session SET owner_user_id = NULL
WHERE owner_user_id IS NOT NULL AND owner_user_id NOT IN (SELECT id FROM king_ai_user);
DELETE FROM king_ai_agent_knowledge WHERE agent_id NOT IN (SELECT id FROM king_ai_agent);
DELETE FROM king_ai_agent_knowledge WHERE knowledge_base_id NOT IN (SELECT id FROM king_ai_knowledge_base);
DELETE FROM king_ai_agent_mcp WHERE agent_id NOT IN (SELECT id FROM king_ai_agent);
DELETE FROM king_ai_agent_mcp WHERE mcp_id NOT IN (SELECT id FROM king_ai_mcp);
DELETE FROM king_ai_agent_skill WHERE agent_id NOT IN (SELECT id FROM king_ai_agent);
DELETE FROM king_ai_agent_skill WHERE skill_id NOT IN (SELECT id FROM king_ai_skill);
DELETE FROM king_ai_skill_file WHERE skill_id NOT IN (SELECT id FROM king_ai_skill);
DELETE FROM king_ai_user_space WHERE user_id NOT IN (SELECT id FROM king_ai_user);
DELETE FROM king_ai_user_space WHERE space_id NOT IN (SELECT id FROM king_ai_space);

-- Optimistic locking.
ALTER TABLE king_ai_user ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_space ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_agent ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_supplier ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_supplier_model ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_mcp ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_skill ADD COLUMN lock_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_knowledge_base ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';
ALTER TABLE king_ai_knowledge_doc ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';

-- Actor audit fields.
ALTER TABLE king_ai_user ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_user ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_space ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_space ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_user_space ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_user_space ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_supplier ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_supplier ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_supplier_model ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_supplier_model ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_agent ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_agent ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_agent_knowledge ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_agent_knowledge ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_agent_mcp ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_agent_mcp ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_agent_skill ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_agent_skill ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_knowledge_base ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_knowledge_base ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_knowledge_doc ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_knowledge_doc ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_knowledge_chunk ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_knowledge_chunk ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_knowledge_index_job ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_knowledge_index_job ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_mcp ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_mcp ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_skill ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_skill ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_skill_file ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_skill_file ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_chat_session ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_chat_session ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';
ALTER TABLE king_ai_chat_message ADD COLUMN created_by BIGINT NULL COMMENT '创建人';
ALTER TABLE king_ai_chat_message ADD COLUMN updated_by BIGINT NULL COMMENT '最后修改人';

-- Foreign keys.
ALTER TABLE king_ai_user_space ADD CONSTRAINT fk_user_space_user
    FOREIGN KEY (user_id) REFERENCES king_ai_user (id) ON DELETE CASCADE;
ALTER TABLE king_ai_user_space ADD CONSTRAINT fk_user_space_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_supplier ADD CONSTRAINT fk_supplier_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_supplier_model ADD CONSTRAINT fk_model_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_supplier_model ADD CONSTRAINT fk_model_supplier
    FOREIGN KEY (supplier_id) REFERENCES king_ai_supplier (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent ADD CONSTRAINT fk_agent_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent ADD CONSTRAINT fk_agent_model
    FOREIGN KEY (model_id) REFERENCES king_ai_supplier_model (id);
ALTER TABLE king_ai_agent_knowledge ADD CONSTRAINT fk_agent_knowledge_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent_knowledge ADD CONSTRAINT fk_agent_knowledge_base
    FOREIGN KEY (knowledge_base_id) REFERENCES king_ai_knowledge_base (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent_mcp ADD CONSTRAINT fk_agent_mcp_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent_mcp ADD CONSTRAINT fk_agent_mcp_server
    FOREIGN KEY (mcp_id) REFERENCES king_ai_mcp (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent_skill ADD CONSTRAINT fk_agent_skill_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;
ALTER TABLE king_ai_agent_skill ADD CONSTRAINT fk_agent_skill_skill
    FOREIGN KEY (skill_id) REFERENCES king_ai_skill (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_base ADD CONSTRAINT fk_kb_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_base ADD CONSTRAINT fk_kb_model
    FOREIGN KEY (embedding_model_id) REFERENCES king_ai_supplier_model (id);
ALTER TABLE king_ai_knowledge_doc ADD CONSTRAINT fk_doc_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_doc ADD CONSTRAINT fk_doc_kb
    FOREIGN KEY (knowledge_base_id) REFERENCES king_ai_knowledge_base (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_chunk ADD CONSTRAINT fk_chunk_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_chunk ADD CONSTRAINT fk_chunk_kb
    FOREIGN KEY (knowledge_base_id) REFERENCES king_ai_knowledge_base (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_chunk ADD CONSTRAINT fk_chunk_doc
    FOREIGN KEY (doc_id) REFERENCES king_ai_knowledge_doc (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chunk_vector ADD CONSTRAINT fk_vector_chunk
    FOREIGN KEY (chunk_id) REFERENCES king_ai_knowledge_chunk (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chunk_vector ADD CONSTRAINT fk_vector_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chunk_vector ADD CONSTRAINT fk_vector_kb
    FOREIGN KEY (knowledge_base_id) REFERENCES king_ai_knowledge_base (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chunk_vector ADD CONSTRAINT fk_vector_doc
    FOREIGN KEY (doc_id) REFERENCES king_ai_knowledge_doc (id) ON DELETE CASCADE;
ALTER TABLE king_ai_mcp ADD CONSTRAINT fk_mcp_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_skill ADD CONSTRAINT fk_skill_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_skill_file ADD CONSTRAINT fk_skill_file_skill
    FOREIGN KEY (skill_id) REFERENCES king_ai_skill (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chat_session ADD CONSTRAINT fk_session_space
    FOREIGN KEY (space_id) REFERENCES king_ai_space (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chat_session ADD CONSTRAINT fk_session_agent
    FOREIGN KEY (agent_id) REFERENCES king_ai_agent (id) ON DELETE CASCADE;
ALTER TABLE king_ai_chat_session ADD CONSTRAINT fk_session_owner
    FOREIGN KEY (owner_user_id) REFERENCES king_ai_user (id) ON DELETE SET NULL;
ALTER TABLE king_ai_chat_message ADD CONSTRAINT fk_message_session
    FOREIGN KEY (session_id) REFERENCES king_ai_chat_session (id) ON DELETE CASCADE;
ALTER TABLE king_ai_knowledge_index_job ADD CONSTRAINT fk_index_job_doc
    FOREIGN KEY (doc_id) REFERENCES king_ai_knowledge_doc (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uk_model_supplier_model ON king_ai_supplier_model (supplier_id, model);

CREATE TABLE IF NOT EXISTS king_ai_audit_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NULL,
    actor_type    VARCHAR(16)   NOT NULL DEFAULT 'USER',
    actor_id      BIGINT        NULL,
    actor_name    VARCHAR(100)  NULL,
    action        VARCHAR(120)  NOT NULL,
    resource_type VARCHAR(60)   NULL,
    resource_id   VARCHAR(64)   NULL,
    status        VARCHAR(16)   NOT NULL,
    detail        VARCHAR(1000) NULL,
    ip            VARCHAR(64)   NULL,
    user_agent    VARCHAR(255)  NULL,
    request_id    VARCHAR(64)   NULL,
    created_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_created (created_at),
    KEY idx_audit_space_created (space_id, created_at),
    KEY idx_audit_actor_created (actor_id, created_at),
    KEY idx_audit_action_created (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理操作审计日志';
