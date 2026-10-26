package com.king.ai.mcp.dto;

import java.util.List;

public record McpTestResult(
        boolean ok,
        String message,
        List<McpToolView> tools
) {
}
