package com.king.ai.mcptest.mcp;

/** 未知工具：映射为 JSON-RPC -32602 协议错误。 */
public class UnknownToolException extends RuntimeException {

    private final String toolName;

    public UnknownToolException(String toolName) {
        super("未知工具: '" + toolName + "'");
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
