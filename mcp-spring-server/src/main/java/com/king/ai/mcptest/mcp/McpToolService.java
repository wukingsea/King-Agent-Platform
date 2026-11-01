package com.king.ai.mcptest.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MCP 测试工具集：echo / add / get_current_time / slow_echo / simulate_error。
 * 与 mcp-test-server（Python 版）行为对齐，便于对比验证平台 MCP 接入。
 */
@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final double DEFAULT_SLEEP_SECONDS = 3.0;
    private static final double MAX_SLEEP_SECONDS = 120.0;

    private final ObjectMapper mapper;

    public McpToolService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 执行工具调用；未知工具抛 UnknownToolException，入参问题返回 isError=true 的结果。 */
    public ObjectNode call(String name, JsonNode arguments) {
        long start = System.currentTimeMillis();
        ObjectNode result;
        try {
            result = dispatch(name, arguments);
        } catch (ToolInputException e) {
            result = textResult(e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = textResult("工具执行被中断", true);
        }
        log.info("工具 {} 执行完成，耗时 {} ms", name, System.currentTimeMillis() - start);
        return result;
    }

    private ObjectNode dispatch(String name, JsonNode args) throws InterruptedException {
        return switch (name) {
            case "echo" -> textResult("echo: " + requireText(args, "text"), false);
            case "add" -> add(args);
            case "get_current_time" -> currentTime();
            case "slow_echo" -> slowEcho(args);
            case "simulate_error" -> simulateError(args);
            default -> throw new UnknownToolException(name);
        };
    }

    private String requireText(JsonNode args, String key) {
        JsonNode value = args.path(key);
        if (!value.isTextual()) {
            throw new ToolInputException("参数 " + key + " 必须是 string");
        }
        return value.asText();
    }

    private ObjectNode add(JsonNode args) {
        JsonNode a = args.path("a");
        JsonNode b = args.path("b");
        if (!a.isNumber() || !b.isNumber()) {
            throw new ToolInputException("参数 a 和 b 必须是数字");
        }
        return textResult("a + b = " + (a.doubleValue() + b.doubleValue()), false);
    }

    private ObjectNode currentTime() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String text = "当前时间：" + now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA))
                + "（Asia/Shanghai）\n星期：" + now.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA));
        return textResult(text, false);
    }

    private ObjectNode slowEcho(JsonNode args) throws InterruptedException {
        String text = requireText(args, "text");
        double seconds = args.path("seconds").asDouble(DEFAULT_SLEEP_SECONDS);
        if (seconds < 0 || seconds > MAX_SLEEP_SECONDS) {
            throw new ToolInputException("seconds 必须在 0 到 " + MAX_SLEEP_SECONDS + " 之间");
        }
        Thread.sleep((long) (seconds * 1000));
        return textResult("等待 " + seconds + " 秒后回显: " + text, false);
    }

    private ObjectNode simulateError(JsonNode args) {
        String extra = args.path("message").asText("");
        String text = "模拟工具执行失败（用于测试错误处理）";
        if (!extra.isBlank()) {
            text = text + "：" + extra;
        }
        return textResult(text, true);
    }

    private ObjectNode textResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    /** 工具清单（含 JSON Schema），与平台 McpClient 的 tools/list 解析兼容。 */
    public List<ObjectNode> listTools() {
        List<ObjectNode> tools = new ArrayList<>();
        ObjectNode echoProps = mapper.createObjectNode();
        echoProps.putObject("text").put("type", "string").put("description", "要回显的文本");
        tools.add(toolDef("echo", "回显输入文本，用于验证 MCP 工具调用链路（支持中文）。", echoProps, "text"));
        ObjectNode addProps = mapper.createObjectNode();
        addProps.putObject("a").put("type", "number").put("description", "第一个加数");
        addProps.putObject("b").put("type", "number").put("description", "第二个加数");
        tools.add(toolDef("add", "计算两个数字之和。", addProps, "a", "b"));
        tools.add(toolDef("get_current_time", "获取服务器当前日期、时间与星期（Asia/Shanghai）。无参数。",
                mapper.createObjectNode()));
        ObjectNode slowProps = mapper.createObjectNode();
        slowProps.putObject("text").put("type", "string").put("description", "要回显的文本");
        slowProps.putObject("seconds").put("type", "number")
                .put("description", "延迟秒数，默认 3，最大 120（用于测试慢响应与超时）");
        tools.add(toolDef("slow_echo", "延迟指定秒数后回显文本，用于测试慢响应与超时场景。", slowProps, "text"));
        ObjectNode errProps = mapper.createObjectNode();
        errProps.putObject("message").put("type", "string").put("description", "附加到错误信息后的说明（可选）");
        tools.add(toolDef("simulate_error", "恒定返回执行失败（isError=true），用于测试错误处理。", errProps));
        return tools;
    }

    private ObjectNode toolDef(String name, String description, ObjectNode properties, String... required) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", properties);
        if (required.length > 0) {
            ArrayNode requiredNode = schema.putArray("required");
            for (String key : required) {
                requiredNode.add(key);
            }
        }
        schema.put("additionalProperties", false);
        return tool;
    }
}
