package com.king.ai.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

@Slf4j
@Component
public class HttpExternalModerationClient implements ExternalModerationClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final OutboundUrlGuard outboundUrlGuard;

    public HttpExternalModerationClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                                        KingAiProperties properties, OutboundUrlGuard outboundUrlGuard) {
        this.httpClient = httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(Math.max(500,
                        properties.getCompliance().getModeration().getExternalTimeoutMs())))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    @Override
    public ModerationDecision check(String text, ModerationContext context) {
        String url = properties.getCompliance().getModeration().getExternalUrl();
        if (Texts.isBlank(url)) {
            throw new ModerationUnavailableException("未配置外部审核服务");
        }
        String violation = outboundUrlGuard.violation(url);
        if (violation != null) {
            throw new ModerationUnavailableException(violation);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", text);
        payload.put("spaceId", context.spaceId());
        payload.put("surface", context.surface());
        payload.put("publicChat", context.publicChat());
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON));
        String token = properties.getCompliance().getModeration().getExternalToken();
        if (!Texts.isBlank(token)) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new ModerationUnavailableException("外部审核服务返回 HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(body.string());
            String action = root.path("action").asText("REVIEW").toUpperCase(Locale.ROOT);
            String category = Texts.blankToDefault(root.path("category").asText(""), "EXTERNAL");
            String reason = Texts.blankToDefault(root.path("reason").asText(""), "外部审核");
            return switch (action) {
                case "ALLOW" -> ModerationDecision.allow();
                case "BLOCK" -> new ModerationDecision(ModerationVerdict.BLOCK, category, reason);
                default -> new ModerationDecision(ModerationVerdict.REVIEW, category, reason);
            };
        } catch (IOException ex) {
            throw new ModerationUnavailableException("外部审核调用失败：" + ex.getMessage(), ex);
        }
    }
}
