package com.king.ai.mcptest.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MCP Streamable HTTP 端点（/mcp），协议参照平台 DemoMcpController 与 MCP 2025-03-26 规范：
 * - 支持 initialize / notifications/initialized / ping / tools/list / tools/call
 * - 通知返回 202 Accepted；initialize 通过 Mcp-Session-Id 响应头下发会话（服务端无状态，不强校验会话）
 * - 兼容 JSON-RPC 批量请求（数组）；解析失败返回 400 + -32700
 * - 可选 Bearer 鉴权：设置环境变量 MCP_TEST_TOKEN 后启用
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private final ObjectMapper mapper;
    private final McpToolService tools;
    private final String authToken;

    public McpController(ObjectMapper mapper, McpToolService tools) {
        this.mapper = mapper;
        this.tools = tools;
        this.authToken = System.getenv("MCP_TEST_TOKEN");
    }

    @PostMapping
    public ResponseEntity<JsonNode> post(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) JsonNode payload) {
        if (authToken != null && !authToken.isBlank() && !("Bearer " + authToken).equals(authorization)) {
            return ResponseEntity.status(401).body(error(null, -32000, "Unauthorized"));
        }
        if (payload == null) {
            return ResponseEntity.ok().body(error(null, -32600, "Invalid Request: 请求体为空"));
        }
        if (payload.isArray()) {
            return respondBatch(payload);
        }
        return respondSingle(payload);
    }

    private ResponseEntity<JsonNode> respondBatch(JsonNode payload) {
        List<JsonNode> responses = new ArrayList<>();
        boolean hasInitialize = false;
        for (JsonNode item : payload) {
            if (item.isObject() && "initialize".equals(item.path("method").asText(""))) {
                hasInitialize = true;
            }
            JsonNode body = singleResponse(item);
            if (body != null) {
                responses.add(body);
            }
        }
        if (responses.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (hasInitialize) {
            builder.header("Mcp-Session-Id", UUID.randomUUID().toString());
        }
        ArrayNode array = mapper.createArrayNode();
        responses.forEach(array::add);
        return builder.body(array);
    }

    private ResponseEntity<JsonNode> respondSingle(JsonNode payload) {
        JsonNode body = singleResponse(payload);
        if (body == null) {
            return ResponseEntity.accepted().build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (payload.isObject() && "initialize".equals(payload.path("method").asText(""))) {
            builder.header("Mcp-Session-Id", UUID.randomUUID().toString());
        }
        return builder.body(body);
    }

    /** 单条 JSON-RPC 消息处理：通知返回 null（→ 202），请求返回响应体。 */
    private JsonNode singleResponse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return error(null, -32600, "Invalid Request");
        }
        JsonNode id = payload.get("id");
        if (id == null) {
            log.info("收到通知: {}", payload.path("method").asText(""));
            return null;
        }
        String method = payload.path("method").asText("");
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        try {
            switch (method) {
                case "initialize" -> response.set("result", initializeResult(payload.path("params")));
                case "ping" -> response.set("result", mapper.createObjectNode());
                case "tools/list" -> response.set("result", toolsListResult());
                case "tools/call" -> response.set("result", tools.call(
                        payload.path("params").path("name").asText(""),
                        payload.path("params").path("arguments")));
                default -> {
                    return error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (UnknownToolException e) {
            return error(id, -32602, "未知工具: '" + e.getToolName() + "'");
        }
        return response;
    }

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText(DEFAULT_PROTOCOL_VERSION);
        String negotiated = SUPPORTED_PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo")
                .put("name", "king-ai-mcp-spring-test")
                .put("version", "1.0.0");
        result.put("instructions", "Spring Boot 版 MCP 测试服务：echo/add/get_current_time/slow_echo/simulate_error。");
        log.info("initialize 完成，协商协议版本: {}（客户端请求: {}）", negotiated, requested);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsNode = result.putArray("tools");
        tools.listTools().forEach(toolsNode::add);
        return result;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    @GetMapping
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(405).header("Allow", "POST, DELETE, OPTIONS").build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        log.info("收到 DELETE，会话结束（服务端无状态，忽略会话清理）");
        return ResponseEntity.noContent().build();
    }

    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options() {
        return ResponseEntity.ok().header("Allow", "POST, DELETE, OPTIONS").build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JsonNode> handleUnreadable() {
        return ResponseEntity.badRequest().body(error(null, -32700, "Parse error: 请求体不是合法 JSON"));
    }
}
