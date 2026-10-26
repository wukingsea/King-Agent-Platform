package com.king.ai.mcp.dto;

import java.time.LocalDateTime;

public record McpView(
        Long id,
        Long spaceId,
        String name,
        String transport,
        String url,
        boolean headersConfigured,
        Integer status,
        String remark,
        long agentCount,
        LocalDateTime createdAt
) {
}
