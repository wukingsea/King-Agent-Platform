package com.king.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_mcp")
public class McpServer extends BaseEntity {

    private Long spaceId;
    private String name;
    private String transport;
    private String url;
    private String headers;
    private Integer status;
    private String remark;
    /** NONE or OAUTH2; when OAUTH2 the client requests a bearer token before connecting. */
    private String authType;
    private String oauthTokenUrl;
    private String oauthClientId;
    private String oauthClientSecret;
    private String oauthScopes;
    private String oauthAccessToken;
    private String oauthRefreshToken;
    private LocalDateTime oauthExpiresAt;

    @Version
    private Integer version;
}
