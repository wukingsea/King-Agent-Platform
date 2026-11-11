package com.king.ai.chat;

import com.king.ai.llm.ToolDefinition;
import com.king.ai.mcp.McpClient;
import com.king.ai.mcp.entity.McpServer;

/**
 * 一次对话中实际绑定给模型的工具：定义 + 执行所需的后端句柄。
 * SKILL 工具不需要 client/server；MCP 工具携带已初始化的客户端与服务端元数据。
 */
public record BoundTool(ToolDefinition definition, ToolKind kind, McpClient client,
                        McpServer server, String remoteName) {

    public String source() {
        return kind == ToolKind.SKILL ? "SKILL" : "MCP";
    }
}
