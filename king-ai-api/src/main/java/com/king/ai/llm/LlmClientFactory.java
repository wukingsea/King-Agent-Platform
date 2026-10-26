package com.king.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import com.king.ai.model.entity.Supplier;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmClientFactory {

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final KingAiProperties properties;

    public LlmClient clientFor(Supplier supplier) {
        if (supplier == null) {
            throw new LlmException("模型供应商不存在");
        }
        String type = Texts.blankToDefault(supplier.getType(), "OPENAI").toUpperCase(Locale.ROOT);
        if ("MOCK".equals(type)) {
            return new MockLlmClient();
        }
        String violation = outboundUrlGuard.violation(supplier.getBaseUrl());
        if (violation != null) {
            throw new LlmException(violation);
        }
        String apiKey = cryptoService.decrypt(supplier.getApiKey());
        return new OpenAiClient(httpClient, objectMapper, supplier.getBaseUrl(), apiKey,
                parseHeaders(cryptoService.decrypt(supplier.getHeaders())), properties.getChat().getLlmTimeoutSeconds());
    }

    public Map<String, String> parseHeaders(String json) {
        if (Texts.isBlank(json)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new LlmException("供应商自定义请求头必须是 JSON 对象");
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
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmException("供应商自定义请求头不是合法 JSON", ex);
        }
    }
}
