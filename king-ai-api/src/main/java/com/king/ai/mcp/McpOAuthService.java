package com.king.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Obtains and renews MCP bearer tokens. A still-valid access token is reused; otherwise the
 * refresh token is exchanged, falling back to the client-credentials grant. Tokens are
 * encrypted before they reach the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpOAuthService {

    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_OAUTH2 = "OAUTH2";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final McpServerMapper mcpServerMapper;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    public boolean isOAuth(McpServer server) {
        return server != null && AUTH_OAUTH2.equalsIgnoreCase(
                Texts.blankToDefault(server.getAuthType(), AUTH_NONE));
    }

    public Map<String, String> authorizationHeaders(McpServer server) {
        if (!isOAuth(server)) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + accessToken(server));
    }

    public String accessToken(McpServer server) {
        if (hasValidToken(server)) {
            return cryptoService.decrypt(server.getOauthAccessToken());
        }
        Long id = server.getId();
        Object lock = id == null ? server : locks.computeIfAbsent(id, key -> new Object());
        synchronized (lock) {
            if (hasValidToken(server)) {
                return cryptoService.decrypt(server.getOauthAccessToken());
            }
            TokenResponse token = requestToken(server);
            persist(server, token);
            return token.accessToken();
        }
    }

    private boolean hasValidToken(McpServer server) {
        if (Texts.isBlank(server.getOauthAccessToken())) {
            return false;
        }
        LocalDateTime expiresAt = server.getOauthExpiresAt();
        return expiresAt == null || expiresAt.isAfter(LocalDateTime.now().plusSeconds(EXPIRY_SKEW_SECONDS));
    }

    private TokenResponse requestToken(McpServer server) {
        String tokenUrl = Texts.blankToDefault(server.getOauthTokenUrl(), "").trim();
        if (tokenUrl.isEmpty()) {
            throw new McpException("MCP OAuth 缺少 Token 地址");
        }
        String violation = outboundUrlGuard.violation(tokenUrl);
        if (violation != null) {
            throw new McpException(violation);
        }
        String clientId = Texts.blankToDefault(server.getOauthClientId(), "").trim();
        if (clientId.isEmpty()) {
            throw new McpException("MCP OAuth 缺少 Client ID");
        }
        String refreshToken = cryptoService.decrypt(server.getOauthRefreshToken());
        FormBody.Builder form = new FormBody.Builder().add("client_id", clientId);
        String clientSecret = cryptoService.decrypt(server.getOauthClientSecret());
        if (!Texts.isBlank(clientSecret)) {
            form.add("client_secret", clientSecret);
        }
        if (!Texts.isBlank(refreshToken)) {
            form.add("grant_type", "refresh_token").add("refresh_token", refreshToken);
        } else {
            form.add("grant_type", "client_credentials");
            if (!Texts.isBlank(server.getOauthScopes())) {
                form.add("scope", server.getOauthScopes().trim());
            }
        }
        Request request = new Request.Builder().url(tokenUrl).post(form.build())
                .header("Accept", "application/json").build();
        try (Response response = httpClient.newBuilder()
                .callTimeout(CALL_TIMEOUT)
                .dns(outboundUrlGuard.dns())
                .build()
                .newCall(request)
                .execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("MCP OAuth token request failed: status={}", response.code());
                throw new McpException("获取 MCP 访问令牌失败（HTTP " + response.code() + "）");
            }
            JsonNode payload = objectMapper.readTree(body.byteStream().readNBytes(1_000_000));
            String accessToken = text(payload, "access_token");
            if (Texts.isBlank(accessToken)) {
                throw new McpException("MCP OAuth 响应缺少 access_token");
            }
            long expiresIn = payload.path("expires_in").asLong(0);
            LocalDateTime expiresAt = expiresIn > 0
                    ? LocalDateTime.now().plusSeconds(expiresIn) : null;
            return new TokenResponse(accessToken, text(payload, "refresh_token"), expiresAt);
        } catch (McpException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("MCP OAuth token request failed: {}", ex.getClass().getSimpleName());
            throw new McpException("获取 MCP 访问令牌失败，请检查 OAuth 配置", ex);
        }
    }

    private void persist(McpServer server, TokenResponse token) {
        server.setOauthAccessToken(cryptoService.encrypt(token.accessToken()));
        if (!Texts.isBlank(token.refreshToken())) {
            server.setOauthRefreshToken(cryptoService.encrypt(token.refreshToken()));
        }
        server.setOauthExpiresAt(token.expiresAt());
        if (server.getId() == null) {
            return;
        }
        LambdaUpdateWrapper<McpServer> update = new LambdaUpdateWrapper<McpServer>()
                .eq(McpServer::getId, server.getId())
                .set(McpServer::getOauthAccessToken, server.getOauthAccessToken())
                .set(McpServer::getOauthExpiresAt, server.getOauthExpiresAt());
        if (!Texts.isBlank(token.refreshToken())) {
            update.set(McpServer::getOauthRefreshToken, server.getOauthRefreshToken());
        }
        mcpServerMapper.update(null, update);
    }

    public static String normalizeAuthType(String value) {
        if (Texts.isBlank(value)) {
            return AUTH_NONE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!AUTH_NONE.equals(normalized) && !AUTH_OAUTH2.equals(normalized)) {
            throw new McpException("MCP 认证方式不合法");
        }
        return normalized;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return Texts.isBlank(text) ? null : text.trim();
    }

    private record TokenResponse(String accessToken, String refreshToken, LocalDateTime expiresAt) {
    }
}
