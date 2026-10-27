-- Track login and API key rotation activity for periodic access reviews.
ALTER TABLE king_ai_user
    ADD COLUMN last_login_at DATETIME NULL COMMENT '最近登录时间';
ALTER TABLE king_ai_supplier
    ADD COLUMN api_key_rotated_at DATETIME NULL COMMENT 'API Key 最近轮换时间';

CREATE TABLE IF NOT EXISTS king_ai_access_review (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    space_id     BIGINT       NOT NULL COMMENT '空间 ID',
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/COMPLETED',
    summary      VARCHAR(1000) NULL COMMENT '复核摘要',
    generated_by BIGINT       NULL COMMENT '生成人',
    generated_at DATETIME     NOT NULL COMMENT '生成时间',
    completed_at DATETIME     NULL COMMENT '完成时间',
    version      INT          NOT NULL DEFAULT 0,
    created_by   BIGINT       NULL,
    created_at   DATETIME     NOT NULL,
    updated_by   BIGINT       NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_review_space (space_id, status),
    CONSTRAINT fk_access_review_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间访问复核';

CREATE TABLE IF NOT EXISTS king_ai_access_review_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    review_id        BIGINT       NOT NULL COMMENT '复核任务 ID',
    space_id         BIGINT       NOT NULL COMMENT '空间 ID',
    subject_type     VARCHAR(20)  NOT NULL COMMENT '主体类型：USER/SUPPLIER/SERVICE_ACCOUNT',
    subject_id       BIGINT       NOT NULL COMMENT '主体 ID',
    subject_name     VARCHAR(200) NOT NULL COMMENT '主体名称快照',
    role             VARCHAR(20)  NULL COMMENT '角色快照',
    last_activity_at DATETIME     NULL COMMENT '最近活动时间',
    flags            VARCHAR(500) NULL COMMENT '风险标记，逗号分隔',
    decision         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/KEEP/REVOKE',
    decided_by       BIGINT       NULL COMMENT '决策人',
    decided_at       DATETIME     NULL COMMENT '决策时间',
    note             VARCHAR(500) NULL COMMENT '决策说明',
    version          INT          NOT NULL DEFAULT 0,
    created_by       BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    updated_by       BIGINT       NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_item_review (review_id, decision),
    CONSTRAINT fk_access_item_review FOREIGN KEY (review_id)
        REFERENCES king_ai_access_review (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='访问复核明细';
