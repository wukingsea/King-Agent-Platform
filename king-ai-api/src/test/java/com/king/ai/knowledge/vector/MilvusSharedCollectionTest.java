package com.king.ai.knowledge.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.observability.KnowledgeMetrics;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusSharedCollectionTest {

    private static final String DESCRIBE = "/v2/vectordb/collections/describe";
    private static final String CREATE = "/v2/vectordb/collections/create";
    private static final String DROP = "/v2/vectordb/collections/drop";
    private static final String SEARCH = "/v2/vectordb/entities/search";
    private static final String DELETE = "/v2/vectordb/entities/delete";
    private static final String UPSERT = "/v2/vectordb/entities/upsert";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<String>> requests = new ConcurrentHashMap<>();
    private HttpServer server;
    private volatile String describeResponse;
    private volatile String searchResponse;

    @BeforeEach
    void setUp() throws IOException {
        describeResponse = "{\"code\":100,\"message\":\"collection not found\"}";
        searchResponse = "{\"code\":0,\"data\":[{\"chunk_id\":7,\"distance\":0.8}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of(DESCRIBE, CREATE, DROP, SEARCH, DELETE, UPSERT)) {
            server.createContext(path, exchange -> handle(path, exchange));
        }
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsOnePartitionedCollectionPerDimension() {
        MilvusVectorStore store = store("shared");

        store.ensureCollection(base(8));
        store.ensureCollection(base(8));

        assertThat(bodies(CREATE)).hasSize(1);
        assertThat(bodies(DESCRIBE)).hasSize(1);
        JsonNode create = parse(bodies(CREATE).get(0));
        assertThat(create.path("collectionName").asText()).isEqualTo("king_ai_chunks_v2_8");
        assertThat(create.path("params").path("partitionsNum").asInt()).isEqualTo(64);
        JsonNode space = field(create.path("schema").path("fields"), "space_id");
        assertThat(space.path("isPartitionKey").asBoolean()).isTrue();
        JsonNode embedding = field(create.path("schema").path("fields"), "embedding");
        assertThat(embedding.path("elementTypeParams").path("dim").asText()).isEqualTo("8");
    }

    @Test
    void rejectsCollectionWithDifferentDimension() {
        describeResponse = "{\"code\":0,\"data\":{\"fields\":[{\"fieldName\":\"embedding\","
                + "\"elementTypeParams\":{\"dim\":\"16\"}}]}}";
        MilvusVectorStore store = store("shared");

        assertThatThrownBy(() -> store.ensureCollection(base(8)))
                .isInstanceOf(VectorStoreException.class)
                .hasMessageContaining("维度");
        assertThat(bodies(CREATE)).isEmpty();
    }

    @Test
    void searchIsScopedToSpaceAndKnowledgeBase() {
        MilvusVectorStore store = store("shared");

        assertThat(store.search(base(8), new float[]{1f, 0f}, 5)).hasSize(1);
        JsonNode search = parse(bodies(SEARCH).get(0));
        assertThat(search.path("collectionName").asText()).isEqualTo("king_ai_chunks_v2_8");
        assertThat(search.path("filter").asText()).isEqualTo("space_id == 1 && kb_id == 2");
    }

    @Test
    void deletesRowsInsteadOfDroppingTheSharedCollection() {
        MilvusVectorStore store = store("shared");
        KnowledgeBase base = base(8);
        base.setVectorCollection("king_ai_chunks_v2_8");

        store.deleteCollection(base);

        assertThat(bodies(DROP)).isEmpty();
        JsonNode delete = parse(bodies(DELETE).get(0));
        assertThat(delete.path("collectionName").asText()).isEqualTo("king_ai_chunks_v2_8");
        assertThat(delete.path("filter").asText()).isEqualTo("space_id == 1 && kb_id == 2");
    }

    @Test
    void dropsTheLegacyPerKnowledgeBaseCollection() {
        MilvusVectorStore store = store("shared");
        KnowledgeBase base = base(8);
        base.setVectorCollection("king_kb_legacy");

        store.deleteCollection(base);

        assertThat(bodies(DELETE)).hasSize(1);
        assertThat(bodies(DROP)).hasSize(1);
        assertThat(parse(bodies(DROP).get(0)).path("collectionName").asText())
                .isEqualTo("king_kb_legacy");
    }

    @Test
    void perKbModeStillUsesOneCollectionPerKnowledgeBase() {
        MilvusVectorStore store = store("per-kb");
        KnowledgeBase base = base(8);
        base.setVectorCollection("king_kb_1");

        store.search(base, new float[]{1f, 0f}, 5);
        store.deleteCollection(base);

        assertThat(parse(bodies(SEARCH).get(0)).path("collectionName").asText())
                .isEqualTo("king_kb_1");
        assertThat(bodies(DELETE)).isEmpty();
        assertThat(parse(bodies(DROP).get(0)).path("collectionName").asText())
                .isEqualTo("king_kb_1");
    }

    private MilvusVectorStore store(String mode) {
        KingAiProperties properties = new KingAiProperties();
        properties.getMilvus().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMilvus().setTimeoutSeconds(5);
        properties.getMilvus().setCollectionMode(mode);
        return new MilvusVectorStore(new OkHttpClient(), new ObjectMapper(), properties,
                new KnowledgeMetrics(new SimpleMeterRegistry()));
    }

    private void handle(String path, HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.computeIfAbsent(path, key -> Collections.synchronizedList(new ArrayList<>())).add(body);
        String response = responseFor(path);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String responseFor(String path) {
        if (DESCRIBE.equals(path)) {
            return describeResponse;
        }
        if (SEARCH.equals(path)) {
            return searchResponse;
        }
        return "{\"code\":0}";
    }

    private List<String> bodies(String path) {
        return requests.getOrDefault(path, List.of());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode field(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.path("fieldName").asText())) {
                return field;
            }
        }
        throw new AssertionError("missing field " + name);
    }

    private static KnowledgeBase base(int dimension) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(2L);
        base.setSpaceId(1L);
        base.setEmbeddingDim(dimension);
        return base;
    }
}
