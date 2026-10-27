-- Per-space retention policy, one row per resource type.
CREATE TABLE IF NOT EXISTS king_ai_retention_policy (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    space_id              BIGINT      NOT NULL,
    resource_type         VARCHAR(20) NOT NULL COMMENT '资源类型：SESSION/MESSAGE/AUDIT/EXPORT',
    retention_days        INT         NOT NULL COMMENT '保留天数',
    enabled               TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用',
    archive_before_delete TINYINT     NOT NULL DEFAULT 1 COMMENT '删除前是否归档',
    version               INT         NOT NULL DEFAULT 0,
    created_by            BIGINT      NULL,
    created_at            DATETIME    NOT NULL,
    updated_by            BIGINT      NULL,
    updated_at            DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_retention_space_type (space_id, resource_type),
    CONSTRAINT fk_retention_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间数据保留策略';

-- Legal holds block retention deletion until released.
CREATE TABLE IF NOT EXISTS king_ai_legal_hold (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    resource_type VARCHAR(20)  NOT NULL COMMENT '资源类型：SESSION/MESSAGE/AUDIT/EXPORT',
    resource_id   BIGINT       NOT NULL COMMENT '资源主键',
    reason        VARCHAR(500) NOT NULL COMMENT '法律保留原因',
    until         DATETIME     NULL COMMENT '保留截止时间，空表示长期',
    released_at   DATETIME     NULL COMMENT '解除时间，空表示生效中',
    released_by   BIGINT       NULL COMMENT '解除人',
    version       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL,
    updated_by    BIGINT       NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_hold_space_type (space_id, resource_type, released_at),
    KEY idx_hold_resource (space_id, resource_id),
    CONSTRAINT fk_hold_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据法律保留';

-- Asynchronous export jobs; file paths are resolved by the worker.
CREATE TABLE IF NOT EXISTS king_ai_export_job (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    export_type   VARCHAR(20)   NOT NULL COMMENT '导出类型：CONVERSATIONS/AUDIT/MEMBERS',
    format        VARCHAR(10)   NOT NULL COMMENT '导出格式：JSONL/CSV',
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/EXPIRED',
    from_at       DATETIME      NULL COMMENT '数据起始时间',
    to_at         DATETIME      NULL COMMENT '数据截止时间',
    file_name     VARCHAR(255)  NULL COMMENT '导出文件名',
    file_path     VARCHAR(1000) NULL COMMENT '导出文件相对路径',
    row_count     BIGINT        NOT NULL DEFAULT 0 COMMENT '导出行数',
    error_message VARCHAR(1000) NULL COMMENT '失败原因',
    requested_by  BIGINT        NULL COMMENT '申请人',
    expires_at    DATETIME      NULL COMMENT '文件过期时间',
    started_at    DATETIME      NULL COMMENT '开始执行时间',
    completed_at  DATETIME      NULL COMMENT '完成时间',
    version       INT           NOT NULL DEFAULT 0,
    created_by    BIGINT        NULL,
    created_at    DATETIME      NOT NULL,
    updated_by    BIGINT        NULL,
    updated_at    DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_export_space_status (space_id, status),
    KEY idx_export_expires (expires_at),
    CONSTRAINT fk_export_space FOREIGN KEY (space_id)
        REFERENCES king_ai_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步导出任务';
