-- Track published version for agents.
ALTER TABLE king_ai_agent ADD COLUMN published_version INT NULL COMMENT '发布时的版本号';
