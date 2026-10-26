ALTER TABLE king_ai_user
    ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '首次登录必须修改密码' AFTER status;

ALTER TABLE king_ai_user_space
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'VIEWER' COMMENT '空间角色: OWNER/ADMIN/EDITOR/VIEWER/AUDITOR' AFTER space_id;

UPDATE king_ai_user_space us
JOIN (
    SELECT space_id, MIN(id) AS first_id
    FROM king_ai_user_space
    GROUP BY space_id
) first_link ON us.id = first_link.first_id
SET us.role = 'OWNER';

ALTER TABLE king_ai_chat_session
    ADD COLUMN owner_user_id BIGINT NULL COMMENT '会话归属用户' AFTER agent_id;

CREATE INDEX idx_session_space_owner ON king_ai_chat_session (space_id, owner_user_id);
CREATE INDEX idx_session_owner ON king_ai_chat_session (owner_user_id);
