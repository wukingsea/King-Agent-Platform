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

        @Pattern(regexp = "(?i)NONE|OAUTH2", message = "认证方式不合法")
        String authType,

        @Size(max = 1000, message = "OAuth Token 地址不能超过 1000 个字符")
        String oauthTokenUrl,

        @Size(max = 200, message = "OAuth Client ID 不能超过 200 个字符")
        String oauthClientId,

        @Size(max = 1000, message = "OAuth Client Secret 不能超过 1000 个字符")
        String oauthClientSecret,

        @Size(max = 500, message = "OAuth Scope 不能超过 500 个字符")
        String oauthScopes,

        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
