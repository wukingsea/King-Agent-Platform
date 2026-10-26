-- Track published version for agents.
ALTER TABLE king_ai_agent ADD COLUMN IF NOT EXISTS published_version INT NULL;
