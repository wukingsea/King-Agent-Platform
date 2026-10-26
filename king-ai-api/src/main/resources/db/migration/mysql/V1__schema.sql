CREATE TABLE IF NOT EXISTS king_ai_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password      VARCHAR(100) NOT NULL,
    nickname      VARCHAR(50)  NOT NULL DEFAULT '',
    email         VARCHAR(100) NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台用户';

CREATE TABLE IF NOT EXISTS king_ai_space (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    code          VARCHAR(50)  NOT NULL,
    description   VARCHAR(255) NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务空间';

CREATE TABLE IF NOT EXISTS king_ai_user_space (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    user_id       BIGINT   NOT NULL,
    space_id      BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_space (user_id, space_id),
    KEY idx_user_space_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户空间关系';

CREATE TABLE IF NOT EXISTS king_ai_supplier (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    name          VARCHAR(80)   NOT NULL,
    type          VARCHAR(20)   NOT NULL DEFAULT 'OPENAI',
    base_url      VARCHAR(500)  NOT NULL,
    api_key       VARCHAR(1000) NULL,
    headers       TEXT          NULL,
    status        TINYINT       NOT NULL DEFAULT 0,
    remark        VARCHAR(500)  NULL,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_supplier_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型供应商';

CREATE TABLE IF NOT EXISTS king_ai_supplier_model (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    supplier_id   BIGINT        NOT NULL,
    name          VARCHAR(80)   NOT NULL,
    model         VARCHAR(150)  NOT NULL,
    type          VARCHAR(20)   NOT NULL DEFAULT 'CHAT',
    dimension     INT           NOT NULL DEFAULT 0,
    status        TINYINT       NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_model_space (space_id),
    KEY idx_model_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商模型';

CREATE TABLE IF NOT EXISTS king_ai_agent (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    space_id         BIGINT        NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    intro            VARCHAR(500)  NULL,
    model_id         BIGINT        NOT NULL,
    system_prompt    MEDIUMTEXT    NULL,
    temperature      DECIMAL(3,2)  NOT NULL DEFAULT 0.70,
    max_tokens       INT           NOT NULL DEFAULT 2048,
    publish_status   TINYINT       NOT NULL DEFAULT 0,
    public_id        VARCHAR(32)   NULL,
    status           TINYINT       NOT NULL DEFAULT 0,
    created_at       DATETIME      NOT NULL,
    updated_at       DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_public_id (public_id),
    KEY idx_agent_space (space_id),
    KEY idx_agent_model (model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体';

CREATE TABLE IF NOT EXISTS king_ai_agent_knowledge (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id           BIGINT   NOT NULL,
    knowledge_base_id  BIGINT   NOT NULL,
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_knowledge (agent_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体知识库绑定';

CREATE TABLE IF NOT EXISTS king_ai_agent_mcp (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id      BIGINT   NOT NULL,
    mcp_id        BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_mcp (agent_id, mcp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体 MCP 绑定';

CREATE TABLE IF NOT EXISTS king_ai_agent_skill (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id      BIGINT   NOT NULL,
    skill_id      BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_skill (agent_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体技能绑定';

CREATE TABLE IF NOT EXISTS king_ai_knowledge_base (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    space_id            BIGINT       NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500) NULL,
    embedding_model_id  BIGINT       NOT NULL,
    chunk_size          INT          NOT NULL DEFAULT 800,
    chunk_overlap       INT          NOT NULL DEFAULT 100,
    top_k               INT          NOT NULL DEFAULT 4,
    vector_collection   VARCHAR(100) NULL,
    embedding_dim       INT          NOT NULL DEFAULT 0,
    status              TINYINT      NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_kb_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库';

CREATE TABLE IF NOT EXISTS king_ai_knowledge_doc (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    space_id          BIGINT        NOT NULL,
    knowledge_base_id BIGINT        NOT NULL,
    name              VARCHAR(200)  NOT NULL,
    source_type       VARCHAR(20)   NOT NULL DEFAULT 'TEXT',
    content           MEDIUMTEXT    NULL,
    chunk_count       INT           NOT NULL DEFAULT 0,
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message     VARCHAR(1000) NULL,
    created_at        DATETIME      NOT NULL,
    updated_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_doc_kb (knowledge_base_id),
    KEY idx_doc_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档';

CREATE TABLE IF NOT EXISTS king_ai_knowledge_chunk (
    id                BIGINT     NOT NULL AUTO_INCREMENT,
    space_id          BIGINT     NOT NULL,
    knowledge_base_id BIGINT     NOT NULL,
    doc_id            BIGINT     NOT NULL,
    chunk_index       INT        NOT NULL,
    content           MEDIUMTEXT NOT NULL,
    char_count        INT        NOT NULL DEFAULT 0,
    created_at        DATETIME   NOT NULL,
    updated_at        DATETIME   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chunk_kb (knowledge_base_id),
    KEY idx_chunk_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识分片';

CREATE TABLE IF NOT EXISTS king_ai_chunk_vector (
    chunk_id          BIGINT     NOT NULL,
    space_id          BIGINT     NOT NULL,
    knowledge_base_id BIGINT     NOT NULL,
    doc_id            BIGINT     NOT NULL,
    embedding         MEDIUMTEXT NOT NULL,
    created_at        DATETIME   NOT NULL,
    updated_at        DATETIME   NOT NULL,
    PRIMARY KEY (chunk_id),
    KEY idx_vector_kb (knowledge_base_id),
    KEY idx_vector_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地向量存储';

CREATE TABLE IF NOT EXISTS king_ai_mcp (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    transport     VARCHAR(20)   NOT NULL DEFAULT 'HTTP',
    url           VARCHAR(1000) NOT NULL,
    headers       TEXT          NULL,
    status        TINYINT       NOT NULL DEFAULT 0,
    remark        VARCHAR(500)  NULL,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mcp_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 服务';

CREATE TABLE IF NOT EXISTS king_ai_skill (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NULL,
    version       VARCHAR(20)  NOT NULL DEFAULT '1.0',
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_space_name (space_id, name),
    KEY idx_skill_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能';

CREATE TABLE IF NOT EXISTS king_ai_skill_file (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    skill_id      BIGINT        NOT NULL,
    parent_id     BIGINT        NOT NULL DEFAULT 0,
    name          VARCHAR(200)  NOT NULL,
    type          TINYINT       NOT NULL DEFAULT 1,
    content       MEDIUMTEXT    NULL,
    locked        TINYINT       NOT NULL DEFAULT 0,
    sort_order    INT           NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_file_parent_name (skill_id, parent_id, name),
    KEY idx_skill_file_skill (skill_id),
    KEY idx_skill_file_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能文件';

CREATE TABLE IF NOT EXISTS king_ai_chat_session (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    space_id         BIGINT       NOT NULL,
    agent_id         BIGINT       NOT NULL,
    source_type      VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    visitor_id       VARCHAR(64)  NOT NULL,
    title            VARCHAR(100) NOT NULL DEFAULT '新对话',
    message_count    INT          NOT NULL DEFAULT 0,
    last_message_at  DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_space (space_id),
    KEY idx_session_agent_visitor (agent_id, visitor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话';

CREATE TABLE IF NOT EXISTS king_ai_chat_message (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    session_id        BIGINT       NOT NULL,
    role              VARCHAR(20)  NOT NULL,
    content           MEDIUMTEXT   NULL,
    reasoning         MEDIUMTEXT   NULL,
    tool_trace        MEDIUMTEXT   NULL,
    citations         MEDIUMTEXT   NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED',
    model             VARCHAR(150) NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_session (session_id),
    KEY idx_message_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息';
