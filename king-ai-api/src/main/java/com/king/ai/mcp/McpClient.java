package com.king.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Texts;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class McpClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final int MAX_TOOL_TEXT = 50000;

    public record RemoteTool(String name, String description, JsonNode inputSchema) {
    }

    public record CallResult(String text, boolean error) {
    }

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final Map<String, String> headers;
    private final AtomicInteger requestIds = new AtomicInteger(1);
    private String sessionId;
    private boolean initialized;

    public McpClient(OkHttpClient httpClient, ObjectMapper objectMapper, String url, Map<String, String> headers) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.url = url;
        this.headers = headers == null ? Map.of() : headers;
    }

    public List<RemoteTool> listTools() {
        initialize();
        List<RemoteTool> tools = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = rpc("tools/list", params);
            JsonNode list = result.path("tools");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String name = item.path("name").asText("");
                    if (name.isBlank() || !seen.add(name)) {
                        continue;
                    }
                    JsonNode schema = item.get("inputSchema");
                    tools.add(new RemoteTool(name, item.path("description").asText(""),
                            schema == null || schema.isNull() ? objectMapper.createObjectNode() : schema));
                }
            }
            cursor = result.path("nextCursor").asText(null);
            if (Texts.isBlank(cursor)) {
                break;
            }
        }
        return tools;
    }

    public CallResult callTool(String name, String arguments) {
        initialize();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", parseArguments(arguments));
        JsonNode result = rpc("tools/call", params);
        boolean error = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("");
                if ("text".equals(type)) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(item.path("text").asText(""));
                }
            }
        }
        if (text.length() == 0 && result.hasNonNull("structuredContent")) {
            text.append(result.get("structuredContent").toString());
        }
        String value = text.length() == 0 ? result.toString() : text.toString();
        if (value.length() > MAX_TOOL_TEXT) {
            value = value.substring(0, MAX_TOOL_TEXT) + "\n…（工具结果已截断）";
        }
        return new CallResult(value, error);
    }

    private JsonNode parseArguments(String arguments) {
        if (Texts.isBlank(arguments)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            if (!node.isObject()) {
                throw new McpException("工具参数必须是 JSON 对象");
            }
            return node;
        } catch (McpException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new McpException("工具参数不是合法 JSON", ex);
        }
    }

    private synchronized void initialize() {
        if (initialized) {
            return;
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "king-ai");
        clientInfo.put("version", "1.0.0");
        JsonNode result = rpc("initialize", params);
        if (!result.hasNonNull("protocolVersion")) {
            log.debug("MCP initialize response without protocolVersion: {}", url);
        }
        sendNotification("notifications/initialized");
        initialized = true;
    }

    private JsonNode rpc(String method, JsonNode params) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        int id = requestIds.getAndIncrement();
        payload.put("id", id);
        payload.put("method", method);
        if (params != null) {
            payload.set("params", params);
        }
        Request request = buildRequest(payload);
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            captureSession(response.header("Mcp-Session-Id"));
            if (!response.isSuccessful() || body == null) {
                String detail = body == null ? "" : safeRead(body);
                throw new McpException("MCP 服务调用失败（HTTP " + response.code() + "）："
                        + Texts.oneLine(detail, 500));
            }
            String contentType = Texts.blankToDefault(response.header("Content-Type"), "").toLowerCase();
            if (contentType.contains("text/event-stream")) {
                return readSseResponse(body.source(), id);
            }
            String text = body.string();
            JsonNode root = objectMapper.readTree(text);
            return extractResult(root, id);
        } catch (IOException ex) {
            throw new McpException("MCP 服务连接失败：" + ex.getMessage(), ex);
        }
    }

    private void sendNotification(String method) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        Request request = buildRequest(payload);
        try (Response response = httpClient.newCall(request).execute()) {
            captureSession(response.header("Mcp-Session-Id"));
            if (!response.isSuccessful()) {
                ResponseBody body = response.body();
                String detail = body == null ? "" : safeRead(body);
                log.warn("MCP notification {} rejected: HTTP {} {}", method, response.code(),
                        Texts.oneLine(detail, 200));
            }
        } catch (IOException ex) {
            throw new McpException("MCP 通知发送失败：" + ex.getMessage(), ex);
        }
    }

    private Request buildRequest(JsonNode payload) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .post(RequestBody.create(payload.toString(), JSON));
        if (!Texts.isBlank(sessionId)) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!Texts.isBlank(entry.getKey()) && entry.getValue() != null) {
                try {
                    builder.header(entry.getKey().trim(), entry.getValue());
                } catch (IllegalArgumentException ex) {
                    log.warn("Ignore invalid MCP header: {}", entry.getKey());
                }
            }
        }
        return builder.build();
    }

    private JsonNode readSseResponse(BufferedSource source, int expectedId) throws IOException {
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(data);
            } catch (Exception ex) {
                continue;
            }
            JsonNode idNode = root.get("id");
            if (idNode != null && idNode.asInt(-1) == expectedId) {
                return extractResult(root, expectedId);
            }
        }
        throw new McpException("MCP 服务未返回 JSON-RPC 响应");
    }

    private JsonNode extractResult(JsonNode root, int expectedId) {
        if (root == null) {
            throw new McpException("MCP 服务返回空响应");
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText(error.toString());
            throw new McpException("MCP 服务返回错误：" + Texts.oneLine(message, 500));
        }
        JsonNode idNode = root.get("id");
        if (idNode != null && !idNode.isNull() && idNode.asInt(-1) != expectedId) {
            throw new McpException("MCP 服务响应 ID 不匹配");
        }
        JsonNode result = root.get("result");
        return result == null || result.isNull() ? objectMapper.createObjectNode() : result;
    }

    private void captureSession(String value) {
        if (!Texts.isBlank(value)) {
            sessionId = value.trim();
        }
    }

    private static String safeRead(ResponseBody body) {
        try {
            String text = body.string();
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        } catch (IOException ex) {
            return "";
        }
    }
}
