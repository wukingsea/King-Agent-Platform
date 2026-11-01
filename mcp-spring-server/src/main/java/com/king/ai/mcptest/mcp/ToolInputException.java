package com.king.ai.mcptest.mcp;

/** 工具执行入参不合法：作为 isError=true 的工具结果返回（不是协议错误）。 */
public class ToolInputException extends RuntimeException {

    public ToolInputException(String message) {
        super(message);
    }
}
