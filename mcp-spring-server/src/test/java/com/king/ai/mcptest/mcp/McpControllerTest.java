package com.king.ai.mcptest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MCP 端点协议测试：握手、通知、工具清单、工具调用与错误路径。 */
@SpringBootTest
@AutoConfigureMockMvc
class McpControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    private byte[] rpc(int id, String method, ObjectNode params) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] toolCall(int id, String name, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return rpc(id, "tools/call", params);
    }

    @Test
    void initializeReturnsProtocolVersionAndSession() throws Exception {
        MvcResult result = mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(rpc(1, "initialize", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("king-ai-mcp-spring-test"))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andReturn();
        assertThat(result.getResponse().getHeader("Mcp-Session-Id")).isNotBlank();
    }

    @Test
    void notificationReturnsAccepted() throws Exception {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(notification.toString().getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());
    }

    @Test
    void toolsListReturnsFiveTools() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(rpc(2, "tools/list", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(5))
                .andExpect(jsonPath("$.result.tools[0].name").value("echo"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"));
    }

    @Test
    void echoSupportsChinese() throws Exception {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("text", "你好 MCP");
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(3, "echo", arguments)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("echo: 你好 MCP"))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void addComputesSum() throws Exception {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("a", 40);
        arguments.put("b", 2.5);
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(4, "add", arguments)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("a + b = 42.5"))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void slowEchoWithZeroDelayReturnsImmediately() throws Exception {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("text", "fast");
        arguments.put("seconds", 0);
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(5, "slow_echo", arguments)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("等待 0.0 秒后回显: fast"))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void simulateErrorReturnsIsErrorResult() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(6, "simulate_error", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("模拟工具执行失败（用于测试错误处理）"))
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void missingRequiredArgumentReturnsIsErrorResult() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(7, "echo", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("参数 text 必须是 string"))
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void unknownToolReturnsProtocolError32602() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolCall(8, "nope", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("未知工具: 'nope'"));
    }

    @Test
    void unknownMethodReturnsProtocolError32601() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(rpc(9, "resources/list", mapper.createObjectNode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.message").value("Method not found: resources/list"));
    }

    @Test
    void invalidJsonReturnsParseError32700() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content("{not json".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void batchOfPingAndNotificationReturnsSingleResult() throws Exception {
        ObjectNode ping = mapper.createObjectNode();
        ping.put("jsonrpc", "2.0");
        ping.put("id", 10);
        ping.put("method", "ping");
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        String batch = "[" + ping + "," + notification + "]";
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(batch.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(10));
    }
}
