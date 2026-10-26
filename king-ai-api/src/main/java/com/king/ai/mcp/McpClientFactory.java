package com.king.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.mcp.entity.McpServer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class McpClientFactory {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_SIZE = 64;

    private final okhttp3.OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

    private record Prepared(String url, Map<String, String> headers) {
    }

    private record CachedClient(String fingerprint, McpClient client, long expiresAt) {
    }

    /**
     * Returns a reusable client for the server, rebuilding it when the connection
     * configuration changed. MCP keeps transport state (session id, initialized flag),
     * so reusing the client avoids a full handshake for every chat message.
     */
    public McpClient create(McpServer server) {
        Prepared prepared = prepare(server);
        Long id = server.getId();
        if (id == null) {
            return build(prepared);
        }
        String fingerprint = fingerprint(prepared);
        long now = System.currentTimeMillis();
        CachedClient cached = cache.get(id);
        if (cached != null && cached.expiresAt() > now && cached.fingerprint().equals(fingerprint)) {
            return cached.client();
        }
        McpClient client = build(prepared);
        cache.put(id, new CachedClient(fingerprint, client, now + CACHE_TTL.toMillis()));
        prune(now);
        return client;
    }

    /**
     * Builds a client without touching the cache; used by connectivity tests so a
     * cached session cannot mask a broken configuration.
     */
    public McpClient createFresh(McpServer server) {
        return build(prepare(server));
    }

    public void invalidate(Long serverId) {
        if (serverId != null) {
            cache.remove(serverId);
        }
    }

    private Prepared prepare(McpServer server) {
        String transport = Texts.blankToDefault(server.getTransport(), "HTTP").toUpperCase(Locale.ROOT);
        if (!"HTTP".equals(transport)) {
            throw new McpException("当前版本仅支持 Streamable HTTP 传输方式（SSE 已停止支持）");
        }
        String violation = outboundUrlGuard.violation(server.getUrl());
        if (violation != null) {
            throw new McpException(violation);
        }
        return new Prepared(server.getUrl().trim(),
                parseHeaders(cryptoService.decrypt(server.getHeaders())));
    }

    private McpClient build(Prepared prepared) {
        return new McpClient(httpClient.newBuilder().callTimeout(CALL_TIMEOUT).build(),
                objectMapper, prepared.url(), prepared.headers());
    }

    private String fingerprint(Prepared prepared) {
        StringBuilder builder = new StringBuilder(prepared.url());
        prepared.headers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append('\n').append(entry.getKey()).append(':').append(entry.getValue()));
        return builder.toString();
    }

    private void prune(long now) {
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        int overflow = cache.size() - MAX_CACHE_SIZE;
        if (overflow <= 0) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    public Map<String, String> parseHeaders(String json) {
        if (Texts.isBlank(json)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new McpException("MCP 自定义请求头必须是 JSON 对象");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    return;
                }
                headers.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
            });
            return headers;
        } catch (McpException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new McpException("MCP 自定义请求头不是合法 JSON", ex);
        }
    }
}
