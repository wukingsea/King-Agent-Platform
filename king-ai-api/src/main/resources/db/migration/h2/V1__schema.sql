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
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON king_ai_user (username);

CREATE TABLE IF NOT EXISTS king_ai_space (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    code          VARCHAR(50)  NOT NULL,
    description   VARCHAR(255) NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_space_code ON king_ai_space (code);

CREATE TABLE IF NOT EXISTS king_ai_user_space (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    user_id       BIGINT   NOT NULL,
    space_id      BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_space ON king_ai_user_space (user_id, space_id);
CREATE INDEX IF NOT EXISTS idx_user_space_space ON king_ai_user_space (space_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_supplier_space ON king_ai_supplier (space_id);

CREATE TABLE IF NOT EXISTS king_ai_supplier_model (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    supplier_id   BIGINT       NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    model         VARCHAR(150) NOT NULL,
    type          VARCHAR(20)  NOT NULL DEFAULT 'CHAT',
    dimension     INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_model_space ON king_ai_supplier_model (space_id);
CREATE INDEX IF NOT EXISTS idx_model_supplier ON king_ai_supplier_model (supplier_id);

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
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_public_id ON king_ai_agent (public_id);
CREATE INDEX IF NOT EXISTS idx_agent_space ON king_ai_agent (space_id);
CREATE INDEX IF NOT EXISTS idx_agent_model ON king_ai_agent (model_id);

CREATE TABLE IF NOT EXISTS king_ai_agent_knowledge (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id           BIGINT   NOT NULL,
    knowledge_base_id  BIGINT   NOT NULL,
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge ON king_ai_agent_knowledge (agent_id, knowledge_base_id);

CREATE TABLE IF NOT EXISTS king_ai_agent_mcp (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id      BIGINT   NOT NULL,
    mcp_id        BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_mcp ON king_ai_agent_mcp (agent_id, mcp_id);

CREATE TABLE IF NOT EXISTS king_ai_agent_skill (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    agent_id      BIGINT   NOT NULL,
    skill_id      BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill ON king_ai_agent_skill (agent_id, skill_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_kb_space ON king_ai_knowledge_base (space_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON king_ai_knowledge_doc (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_doc_space ON king_ai_knowledge_doc (space_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON king_ai_knowledge_chunk (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON king_ai_knowledge_chunk (doc_id);

CREATE TABLE IF NOT EXISTS king_ai_chunk_vector (
    chunk_id          BIGINT     NOT NULL,
    space_id          BIGINT     NOT NULL,
    knowledge_base_id BIGINT     NOT NULL,
    doc_id            BIGINT     NOT NULL,
    embedding         MEDIUMTEXT NOT NULL,
    created_at        DATETIME   NOT NULL,
    updated_at        DATETIME   NOT NULL,
    PRIMARY KEY (chunk_id)
);
CREATE INDEX IF NOT EXISTS idx_vector_kb ON king_ai_chunk_vector (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_vector_doc ON king_ai_chunk_vector (doc_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_mcp_space ON king_ai_mcp (space_id);

CREATE TABLE IF NOT EXISTS king_ai_skill (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NULL,
    version       VARCHAR(20)  NOT NULL DEFAULT '1.0',
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_space_name ON king_ai_skill (space_id, name);
CREATE INDEX IF NOT EXISTS idx_skill_space ON king_ai_skill (space_id);

CREATE TABLE IF NOT EXISTS king_ai_skill_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    skill_id      BIGINT       NOT NULL,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    name          VARCHAR(200) NOT NULL,
    type          TINYINT      NOT NULL DEFAULT 1,
    content       MEDIUMTEXT   NULL,
    locked        TINYINT      NOT NULL DEFAULT 0,
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_file_parent_name ON king_ai_skill_file (skill_id, parent_id, name);
CREATE INDEX IF NOT EXISTS idx_skill_file_skill ON king_ai_skill_file (skill_id);
CREATE INDEX IF NOT EXISTS idx_skill_file_parent ON king_ai_skill_file (parent_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_session_space ON king_ai_chat_session (space_id);
CREATE INDEX IF NOT EXISTS idx_session_agent_visitor ON king_ai_chat_session (agent_id, visitor_id);

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
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_message_session ON king_ai_chat_message (session_id);
CREATE INDEX IF NOT EXISTS idx_message_created ON king_ai_chat_message (created_at);
