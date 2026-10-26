package com.king.ai.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record McpSaveRequest(
        @NotBlank(message = "MCP 名称不能为空")
        @Size(max = 100, message = "MCP 名称不能超过 100 个字符")
        String name,

        @Pattern(regexp = "(?i)HTTP|STREAMABLE_HTTP|SSE", message = "传输方式不合法")
        String transport,

        @NotBlank(message = "MCP 服务地址不能为空")
        @Size(max = 1000, message = "MCP 服务地址不能超过 1000 个字符")
        String url,

        @Size(max = 8000, message = "自定义请求头不能超过 8000 个字符")
        String headers,

        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
