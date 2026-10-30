package com.king.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class McpOAuthServiceTest {

    private McpOAuthService service;

    @BeforeEach
    void setUp() {
        service = new McpOAuthService(mock(McpServerMapper.class), new OkHttpClient(),
                new ObjectMapper(), CryptoServiceHolder.INSTANCE, mock(OutboundUrlGuard.class));
    }

    @Test
    void noneAuthAddsNoHeaders() {
        McpServer server = new McpServer();
        server.setAuthType("NONE");

        assertThat(service.authorizationHeaders(server)).isEmpty();
    }

    @Test
    void validTokenIsDecryptedIntoBearerHeader() {
        McpServer server = new McpServer();
        server.setAuthType("OAUTH2");
        server.setOauthAccessToken(CryptoServiceHolder.INSTANCE.encrypt("token-1"));
        server.setOauthExpiresAt(LocalDateTime.now().plusHours(1));

        assertThat(service.authorizationHeaders(server))
                .containsEntry("Authorization", "Bearer token-1");
    }

    @Test
    void expiredTokenWithoutEndpointExplainsMissingConfiguration() {
        McpServer server = new McpServer();
        server.setAuthType("OAUTH2");
        server.setOauthAccessToken(CryptoServiceHolder.INSTANCE.encrypt("stale"));
        server.setOauthExpiresAt(LocalDateTime.now().minusMinutes(5));

        assertThatThrownBy(() -> service.authorizationHeaders(server))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Token 地址");
    }

    @Test
    void rejectsUnknownAuthType() {
        assertThatThrownBy(() -> McpOAuthService.normalizeAuthType("BASIC"))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("不合法");
        assertThat(McpOAuthService.normalizeAuthType(null)).isEqualTo("NONE");
    }

    private static final class CryptoServiceHolder {
        private static final CryptoService INSTANCE = new CryptoService(new KingAiProperties());
    }
}
