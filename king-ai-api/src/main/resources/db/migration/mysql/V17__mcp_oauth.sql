-- MCP OAuth2 client credentials / refresh token support.
ALTER TABLE king_ai_mcp ADD COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '认证方式：NONE/OAUTH2';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_token_url VARCHAR(1000) NULL COMMENT 'OAuth2 token 端点';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_client_id VARCHAR(200) NULL COMMENT 'OAuth2 client_id';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_client_secret VARCHAR(1000) NULL COMMENT 'OAuth2 client_secret（加密）';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_scopes VARCHAR(500) NULL COMMENT 'OAuth2 scopes（空格分隔）';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_access_token VARCHAR(4000) NULL COMMENT '缓存 access_token（加密）';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_refresh_token VARCHAR(4000) NULL COMMENT '缓存 refresh_token（加密）';
ALTER TABLE king_ai_mcp ADD COLUMN oauth_expires_at DATETIME NULL COMMENT 'access_token 过期时间';
