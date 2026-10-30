-- MCP OAuth2 client credentials / refresh token support.
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS auth_type VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_token_url VARCHAR(1000) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_client_id VARCHAR(200) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_client_secret VARCHAR(1000) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_scopes VARCHAR(500) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_access_token VARCHAR(4000) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_refresh_token VARCHAR(4000) NULL;
ALTER TABLE king_ai_mcp ADD COLUMN IF NOT EXISTS oauth_expires_at DATETIME NULL;
