package com.king.ai.knowledge.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.dto.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 外部重排客户端：调用兼容 Jina/Cohere 风格 {@code POST {endpoint}} 的
 * rerank API，请求体 {@code {model, query, documents, top_n}}，响应取
 * {@code results[].index/relevance_score}。任何 HTTP/解析失败都抛出异常，
 * 由调用方降级为原始融合顺序。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "king-ai.rag.rerank", name = "mode", havingValue = "http")
public class HttpRerankClient implements RerankClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxCandidates;

    public HttpRerankClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                            KingAiProperties properties) {
        KingAiProperties.Rerank config = properties.getRag().getRerank();
        int timeout = Math.max(2, config.getTimeoutSeconds());
        this.httpClient = httpClient.newBuilder()
                .callTimeout(Duration.ofSeconds(timeout))
                .build();
        this.objectMapper = objectMapper;
        this.endpoint = config.getEndpoint().trim();
        this.apiKey = config.getApiKey();
        this.model = config.getModel();
        this.maxCandidates = Math.max(0, config.getMaxCandidates());
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        if (Texts.isBlank(endpoint)) {
            throw new IllegalStateException("king-ai.rag.rerank.endpoint 未配置");
        }
        List<RetrievedChunk> input = candidates;
        if (maxCandidates > 0 && input.size() > maxCandidates) {
            input = input.subList(0, maxCandidates);
        }
        if (input.size() < 2) {
            return input;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query == null ? "" : query);
        ArrayNode documents = body.putArray("documents");
        for (RetrievedChunk chunk : input) {
            documents.add(chunk.content() == null ? "" : chunk.content());
        }
        body.put("top_n", input.size());

        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (!Texts.isBlank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        JsonNode root;
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Rerank API 请求失败（HTTP "
                        + response.code() + "）：" + Texts.oneLine(text, 300));
            }
            root = objectMapper.readTree(text);
        } catch (IOException ex) {
            throw new IllegalStateException("Rerank API 请求失败：" + ex.getMessage(), ex);
        }

        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return input;
        }
        List<Ranked> ranked = new ArrayList<>(input.size());
        boolean[] seen = new boolean[input.size()];
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= input.size() || seen[index]) {
                continue;
            }
            seen[index] = true;
            ranked.add(new Ranked(index, item.path("relevance_score").asDouble(0)));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        List<RetrievedChunk> reordered = new ArrayList<>(input.size());
        for (Ranked item : ranked) {
            reordered.add(input.get(item.index()));
        }
        // API 未返回的候选按原顺序补到末尾，保证元素不丢失。
        for (int i = 0; i < input.size(); i++) {
            if (!seen[i]) {
                reordered.add(input.get(i));
            }
        }
        return reordered;
    }

    private record Ranked(int index, double score) {
    }
}
