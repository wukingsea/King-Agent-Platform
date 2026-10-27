package com.king.ai.knowledge.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.observability.KnowledgeMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusMetricsTest {

    private HttpServer server;
    private SimpleMeterRegistry registry;
    private MilvusVectorStore store;
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{\"code\":0,\"data\":[]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/vectordb/entities/search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        registry = new SimpleMeterRegistry();
        KingAiProperties properties = new KingAiProperties();
        properties.getMilvus().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMilvus().setTimeoutSeconds(5);
        store = new MilvusVectorStore(new OkHttpClient(), new ObjectMapper(), properties,
                new KnowledgeMetrics(registry));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void recordsSuccessfulSearch() {
        responseBody = "{\"code\":0,\"data\":[{\"chunk_id\":1,\"distance\":0.9}]}";

        assertThat(store.search(base(), new float[]{1f, 2f}, 3)).hasSize(1);
        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "success").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void recordsFailedSearch() {
        responseStatus = 500;
        responseBody = "{\"code\":1,\"message\":\"boom\"}";

        assertThatThrownBy(() -> store.search(base(), new float[]{1f}, 3))
                .isInstanceOf(VectorStoreException.class);
        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "error").timer().count())
                .isEqualTo(1L);
    }

    private KnowledgeBase base() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(1L);
        base.setSpaceId(1L);
        base.setVectorCollection("kb_1");
        return base;
    }
}
