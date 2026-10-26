package com.king.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Texts;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Slf4j
public class OpenAiClient implements LlmClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int ERROR_BODY_LIMIT = 2000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> extraHeaders;

    public OpenAiClient(OkHttpClient httpClient, ObjectMapper objectMapper, String baseUrl, String apiKey,
                        Map<String, String> extraHeaders, int timeoutSeconds) {
        this.httpClient = timeoutSeconds > 0
                ? httpClient.newBuilder().callTimeout(Duration.ofSeconds(timeoutSeconds)).build()
                : httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.extraHeaders = extraHeaders == null ? Map.of() : extraHeaders;
    }

    @Override
    public LlmResult streamChat(LlmChatRequest request, LlmStreamListener listener, CancellationToken token) {
        token.throwIfCancelled();
        String json = buildChatBody(request);
        Request httpRequest = requestBuilder()
                .url(endpoint("/chat/completions"))
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(json, JSON))
                .build();
        Call call = httpClient.newCall(httpRequest);
        token.attach(call);

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
        String[] finishReason = {null};
        int[] usage = {0, 0};

        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new LlmException(errorMessage(response.code(), body == null ? "" : safeBody(body)));
            }
            String contentType = Texts.blankToDefault(response.header("Content-Type"), "").toLowerCase();
            if (contentType.contains("json")) {
                JsonNode root = objectMapper.readTree(body.string());
                return readFullResponse(root, listener, token);
            }
            BufferedSource source = body.source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                token.throwIfCancelled();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(payload);
                } catch (Exception ex) {
                    log.debug("Ignore malformed SSE chunk: {}", Texts.oneLine(payload, 200));
                    continue;
                }
                throwIfError(chunk);
                JsonNode choices = chunk.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.has("delta") ? choice.path("delta") : choice.path("message");
                    String reasoningDelta = firstText(delta.get("reasoning_content"), delta.get("reasoning"));
                    if (!Texts.isBlank(reasoningDelta)) {
                        reasoning.append(reasoningDelta);
                        if (listener != null) {
                            listener.onReasoning(reasoningDelta);
                        }
                    }
                    String contentDelta = textOf(delta.get("content"));
                    if (!Texts.isBlank(contentDelta)) {
                        content.append(contentDelta);
                        if (listener != null) {
                            listener.onContent(contentDelta);
                        }
                    }
                    readToolCallDeltas(delta.get("tool_calls"), calls);
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason[0] = choice.get("finish_reason").asText();
                    }
                }
                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage[0] = usageNode.path("prompt_tokens").asInt(usage[0]);
                    usage[1] = usageNode.path("completion_tokens").asInt(usage[1]);
                }
            }
        } catch (IOException ex) {
            if (token.isCancelled()) {
                throw new LlmCancelledException();
            }
            throw new LlmException("模型服务连接中断：" + ex.getMessage(), ex);
        }

        List<ToolCall> toolCalls = buildToolCalls(calls);
        String finish = finishReason[0] != null ? finishReason[0] : (toolCalls.isEmpty() ? "stop" : "tool_calls");
        return new LlmResult(content.toString(), reasoning.toString(), toolCalls, finish, usage[0], usage[1]);
    }

    @Override
    public List<float[]> embed(String model, List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode array = body.putArray("input");
        for (String input : inputs) {
            array.add(input == null ? "" : input);
        }
        Request request = requestBuilder()
                .url(endpoint("/embeddings"))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new LlmException(errorMessage(response.code(), responseBody == null ? "" : safeBody(responseBody)));
            }
            JsonNode root = objectMapper.readTree(responseBody.string());
            throwIfError(root);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new LlmException("向量服务返回格式不正确");
            }
            TreeMap<Integer, float[]> ordered = new TreeMap<>();
            for (int i = 0; i < data.size(); i++) {
                JsonNode item = data.get(i);
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) {
                    continue;
                }
                int index = item.path("index").asInt(i);
                float[] vector = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    vector[j] = (float) embedding.get(j).asDouble();
                }
                ordered.put(index, vector);
            }
            if (ordered.size() != inputs.size()) {
                throw new LlmException("向量服务返回条数与请求不一致");
            }
            return new ArrayList<>(ordered.values());
        } catch (IOException ex) {
            throw new LlmException("向量服务连接失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<String> listModels() {
        Request request = requestBuilder()
                .url(endpoint("/models"))
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new LlmException(errorMessage(response.code(), body == null ? "" : safeBody(body)));
            }
            JsonNode root = objectMapper.readTree(body.string());
            throwIfError(root);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new LlmException("模型列表返回格式不正确");
            }
            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(id);
                }
                if (models.size() >= 500) {
                    break;
                }
            }
            return models;
        } catch (IOException ex) {
            throw new LlmException("模型列表获取失败：" + ex.getMessage(), ex);
        }
    }

    private String buildChatBody(LlmChatRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        ArrayNode messages = body.putArray("messages");
        for (LlmMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            boolean hasToolCalls = message.toolCalls() != null && !message.toolCalls().isEmpty();
            if (hasToolCalls) {
                node.put("content", message.content() == null ? "" : message.content());
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                }
            } else if ("tool".equals(message.role())) {
                node.put("tool_call_id", message.toolCallId());
                node.put("content", message.content() == null ? "" : message.content());
            } else {
                node.put("content", message.content() == null ? "" : message.content());
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("type", "function");
                ObjectNode function = node.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description() == null ? "" : tool.description());
                if (tool.parameters() != null) {
                    function.set("parameters", tool.parameters());
                } else {
                    ObjectNode parameters = function.putObject("parameters");
                    parameters.put("type", "object");
                    parameters.putObject("properties");
                }
            }
        }
        BigDecimal temperature = request.temperature();
        if (temperature != null) {
            body.put("temperature", temperature.doubleValue());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        return body.toString();
    }

    private LlmResult readFullResponse(JsonNode root, LlmStreamListener listener, CancellationToken token) {
        throwIfError(root);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("模型服务未返回有效回答");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.has("message") ? choice.path("message") : choice.path("delta");
        String content = textOf(message.get("content"));
        String reasoning = firstText(message.get("reasoning_content"), message.get("reasoning"));
        if (content == null) {
            content = textOf(choice.get("text"));
        }
        token.throwIfCancelled();
        if (!Texts.isBlank(reasoning) && listener != null) {
            listener.onReasoning(reasoning);
        }
        if (!Texts.isBlank(content) && listener != null) {
            listener.onContent(content);
        }
        ArrayNode toolCallsNode = message.has("tool_calls") && message.get("tool_calls").isArray()
                ? (ArrayNode) message.get("tool_calls")
                : objectMapper.createArrayNode();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode call = toolCallsNode.get(i);
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String id = call.path("id").asText("");
            if (id.isBlank()) {
                id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            String arguments = function.path("arguments").asText("{}");
            toolCalls.add(new ToolCall(id, name, Texts.isBlank(arguments) ? "{}" : arguments));
        }
        JsonNode usage = root.path("usage");
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        String finish = choice.path("finish_reason").asText(toolCalls.isEmpty() ? "stop" : "tool_calls");
        return new LlmResult(Texts.blankToDefault(content, ""), Texts.blankToDefault(reasoning, ""),
                toolCalls, finish, promptTokens, completionTokens);
    }

    private void readToolCallDeltas(JsonNode toolCalls, Map<Integer, ToolCallBuilder> calls) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonNode call = toolCalls.get(i);
            int index = call.path("index").asInt(i);
            ToolCallBuilder builder = calls.computeIfAbsent(index, key -> new ToolCallBuilder());
            String id = call.path("id").asText("");
            if (!id.isBlank()) {
                builder.id = id;
            }
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (!name.isBlank()) {
                builder.name = name;
            }
            JsonNode arguments = function.get("arguments");
            if (arguments != null && arguments.isTextual()) {
                builder.arguments.append(arguments.asText());
            }
        }
    }

    private static List<ToolCall> buildToolCalls(Map<Integer, ToolCallBuilder> calls) {
        List<ToolCall> result = new ArrayList<>();
        for (ToolCallBuilder builder : calls.values()) {
            if (Texts.isBlank(builder.name)) {
                continue;
            }
            String id = Texts.isBlank(builder.id)
                    ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                    : builder.id;
            String arguments = builder.arguments.length() == 0 ? "{}" : builder.arguments.toString();
            result.add(new ToolCall(id, builder.name, arguments));
        }
        return result;
    }

    private Request.Builder requestBuilder() {
        Request.Builder builder = new Request.Builder()
                .header("User-Agent", "KingAI/1.0");
        if (!Texts.isBlank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            if (Texts.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            try {
                builder.header(entry.getKey().trim(), entry.getValue());
            } catch (IllegalArgumentException ex) {
                log.warn("Ignore invalid custom header: {}", entry.getKey());
            }
        }
        return builder;
    }

    private String endpoint(String path) {
        return baseUrl + path;
    }

    private String errorMessage(int code, String rawBody) {
        String detail = "";
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                detail = error.isTextual() ? error.asText() : firstText(error.get("message"), error.get("msg"));
            }
            if (Texts.isBlank(detail)) {
                detail = firstText(root.get("message"), root.get("detail"));
            }
        } catch (Exception ignored) {
            detail = rawBody;
        }
        if (Texts.isBlank(detail)) {
            detail = code == 401 ? "鉴权失败，请检查 API Key" : "请检查供应商地址与模型配置";
        }
        return "模型服务调用失败（HTTP " + code + "）：" + Texts.oneLine(detail, ERROR_BODY_LIMIT);
    }

    private void throwIfError(JsonNode node) {
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isTextual() ? error.asText() : firstText(error.get("message"), error.get("msg"));
            throw new LlmException("模型服务返回错误：" + Texts.oneLine(
                    Texts.blankToDefault(message, error.toString()), 800));
        }
    }

    private static String safeBody(ResponseBody body) {
        try {
            String value = body.string();
            return value.length() > 4000 ? value.substring(0, 4000) : value;
        } catch (IOException ex) {
            return "";
        }
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = textOf(node);
            if (!Texts.isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.has("text")) {
                    String text = textOf(part.get("text"));
                    if (text != null) {
                        builder.append(text);
                    }
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String base = Texts.isBlank(baseUrl) ? "https://api.openai.com/v1" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        for (String suffix : List.of("/chat/completions", "/embeddings", "/models")) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
