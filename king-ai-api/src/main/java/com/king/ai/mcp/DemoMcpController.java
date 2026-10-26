package com.king.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@RestController
@RequestMapping("/api/demo/mcp")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "king-ai.demo", name = "enabled", havingValue = "true")
public class DemoMcpController {

    private static final String SESSION_ID = "king-ai-demo-session";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@RequestBody(required = false) JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return ResponseEntity.ok().header("Mcp-Session-Id", SESSION_ID).body(error(null, -32600, "Invalid Request"));
        }
        boolean notification = !payload.has("id");
        if (notification) {
            return ResponseEntity.accepted().header("Mcp-Session-Id", SESSION_ID).build();
        }
        JsonNode id = payload.get("id");
        String method = payload.path("method").asText("");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        switch (method) {
            case "initialize" -> response.set("result", initializeResult());
            case "ping" -> response.set("result", objectMapper.createObjectNode());
            case "tools/list" -> response.set("result", toolsResult());
            case "tools/call" -> response.set("result", callResult(payload.path("params")));
            default -> {
                return ResponseEntity.ok().header("Mcp-Session-Id", SESSION_ID)
                        .body(error(id, -32601, "Method not found: " + method));
            }
        }
        return ResponseEntity.ok().header("Mcp-Session-Id", SESSION_ID).body(response);
    }

    private ObjectNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2025-03-26");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "king-ai-demo-mcp");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private ObjectNode toolsResult() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode time = tools.addObject();
        time.put("name", "get_current_time");
        time.put("description", "获取当前日期、时间与星期（Asia/Shanghai）。当用户询问现在几点、今天日期时调用。");
        ObjectNode timeSchema = time.putObject("inputSchema");
        timeSchema.put("type", "object");
        timeSchema.putObject("properties");
        timeSchema.put("additionalProperties", false);

        ObjectNode echo = tools.addObject();
        echo.put("name", "echo");
        echo.put("description", "回显输入内容，用于验证 MCP 工具调用链路。");
        ObjectNode echoSchema = echo.putObject("inputSchema");
        echoSchema.put("type", "object");
        ObjectNode properties = echoSchema.putObject("properties");
        properties.putObject("text").put("type", "string").put("description", "要回显的文本");
        ArrayNode required = echoSchema.putArray("required");
        required.add("text");
        echoSchema.put("additionalProperties", false);
        return result;
    }

    private ObjectNode callResult(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        if ("get_current_time".equals(name)) {
            ZonedDateTime now = ZonedDateTime.now(ZONE);
            String text = "当前时间：" + now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA))
                    + "（Asia/Shanghai）\n星期：" + now.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA));
            content.addObject().put("type", "text").put("text", text);
            result.put("isError", false);
        } else if ("echo".equals(name)) {
            String text = arguments.path("text").asText("");
            content.addObject().put("type", "text").put("text", text.isEmpty() ? "（空内容）" : text);
            result.put("isError", false);
        } else {
            content.addObject().put("type", "text").put("text", "未知工具：" + name);
            result.put("isError", true);
        }
        return result;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
