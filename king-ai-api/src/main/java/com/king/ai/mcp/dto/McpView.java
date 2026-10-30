package com.king.ai.mcp.dto;

import java.time.LocalDateTime;

public record McpView(
        Long id,
        Long spaceId,
        String name,
        String transport,
        String url,
        boolean headersConfigured,
        String authType,
        String oauthTokenUrl,
        String oauthClientId,
        String oauthScopes,
        boolean clientSecretConfigured,
        boolean tokenObtained,
        LocalDateTime oauthExpiresAt,
        Integer status,
        String remark,
        long agentCount,
        LocalDateTime createdAt
) {
}
