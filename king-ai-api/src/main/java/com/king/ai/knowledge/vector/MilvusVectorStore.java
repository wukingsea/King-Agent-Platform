package com.king.ai.knowledge.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.king.ai.common.Ids;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.observability.KnowledgeMetrics;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "king-ai", name = "vector-store", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStore implements VectorStore {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int UPSERT_BATCH = 200;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;
    private final KnowledgeMetrics metrics;
    private final boolean sharedMode;
    private final String collectionPrefix;
    private final int partitionsNum;
    private final Set<String> readyCollections = ConcurrentHashMap.newKeySet();
    private static final long HEALTH_CACHE_MILLIS = 5_000L;
    private volatile long healthCheckedAt;
    private volatile boolean healthUp;

    public MilvusVectorStore(OkHttpClient httpClient, ObjectMapper objectMapper,
                             KingAiProperties properties, KnowledgeMetrics metrics) {
        int timeout = Math.max(5, properties.getMilvus().getTimeoutSeconds());
        this.httpClient = httpClient.newBuilder()
                .callTimeout(Duration.ofSeconds(timeout))
                .build();
        this.objectMapper = objectMapper;
        String base = Texts.blankToDefault(properties.getMilvus().getBaseUrl(), "http://localhost:19530").trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v2/vectordb")) {
            base = base.substring(0, base.length() - "/v2/vectordb".length());
        }
        this.baseUrl = base;
        this.token = properties.getMilvus().getToken();
        this.metrics = metrics;
        this.sharedMode = !isPerKbMode(properties.getMilvus().getCollectionMode());
        this.collectionPrefix = Texts.blankToDefault(
                properties.getMilvus().getCollectionPrefix(), "king_ai_chunks_v2").trim();
        this.partitionsNum = Math.max(1, properties.getMilvus().getPartitionsNum());
    }

    @Override
    public synchronized void ensureCollection(KnowledgeBase base) {
        String collection = collectionName(base);
        if (readyCollections.contains(collection)) {
            return;
        }
        int dimension = dimension(base);
        JsonNode describe = post("/v2/vectordb/collections/describe",
                objectMapper.createObjectNode().put("collectionName", collection), false);
        if (isSuccess(describe)) {
            verifyDimension(collection, dimension, describe);
            readyCollections.add(collection);
            return;
        }
        try {
            post("/v2/vectordb/collections/create", createBody(collection, dimension), true);
            log.info("Milvus collection created: {}, dimension={}, shared={}",
                    collection, dimension, sharedMode);
        } catch (VectorStoreException ex) {
            // Another instance may have created the collection between describe and create.
            JsonNode retry = post("/v2/vectordb/collections/describe",
                    objectMapper.createObjectNode().put("collectionName", collection), false);
            if (!isSuccess(retry)) {
                throw ex;
            }
            verifyDimension(collection, dimension, retry);
        }
        readyCollections.add(collection);
    }

    @Override
    public String storageName(KnowledgeBase base) {
        if (sharedMode) {
            return collectionPrefix + "_" + dimension(base);
        }
        return "king_kb_" + Ids.publicId().substring(0, 24);
    }

    @Override
    public void upsert(KnowledgeBase base, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        ensureCollection(base);
        String collection = collectionName(base);
        for (int start = 0; start < records.size(); start += UPSERT_BATCH) {
            int end = Math.min(records.size(), start + UPSERT_BATCH);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("collectionName", collection);
            ArrayNode data = body.putArray("data");
            for (int i = start; i < end; i++) {
                VectorRecord record = records.get(i);
                ObjectNode item = data.addObject();
                item.put("chunk_id", record.chunkId());
                item.put("doc_id", record.docId());
                item.put("space_id", base.getSpaceId());
                item.put("kb_id", base.getId());
                ArrayNode embedding = item.putArray("embedding");
                for (float value : record.embedding()) {
                    embedding.add(value);
                }
            }
            post("/v2/vectordb/entities/upsert", body, true);
        }
    }

    @Override
    public void deleteDoc(KnowledgeBase base, Long docId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collectionName(base));
        body.put("filter", scopeFilter(base) + " && doc_id == " + docId);
        post("/v2/vectordb/entities/delete", body, false);
    }

    @Override
    public void deleteCollection(KnowledgeBase base) {
        String collection = collectionName(base);
        if (sharedMode) {
            // The collection is shared, so only this knowledge base's rows may be removed.
            ObjectNode body = objectMapper.createObjectNode();
            body.put("collectionName", collection);
            body.put("filter", scopeFilter(base));
            try {
                post("/v2/vectordb/entities/delete", body, false);
            } catch (VectorStoreException ex) {
                log.warn("Failed to delete vectors of knowledge base {} from {}: {}",
                        base.getId(), collection, ex.getMessage());
            }
            dropLegacyCollection(base, collection);
            return;
        }
        ObjectNode body = objectMapper.createObjectNode().put("collectionName", collection);
        try {
            post("/v2/vectordb/collections/drop", body, false);
            readyCollections.remove(collection);
        } catch (VectorStoreException ex) {
            log.warn("Failed to drop Milvus collection {}: {}", collection, ex.getMessage());
        }
    }

    @Override
    public List<VectorHit> search(KnowledgeBase base, float[] query, int topK) {
        return search(base, query, topK, java.util.Set.of());
    }

    @Override
    public List<VectorHit> search(KnowledgeBase base, float[] query, int topK,
                                  java.util.Set<Long> deniedDocIds) {
        String collection = collectionName(base);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        ArrayNode data = body.putArray("data");
        ArrayNode vector = data.addArray();
        for (float value : query) {
            vector.add(value);
        }
        body.put("annsField", "embedding");
        body.put("limit", topK);
        ArrayNode outputFields = body.putArray("outputFields");
        outputFields.add("chunk_id");
        outputFields.add("doc_id");
        body.put("filter", scopeFilter(base) + deniedFilter(deniedDocIds));
        JsonNode root;
        try {
            root = post("/v2/vectordb/entities/search", body, true);
        } catch (VectorStoreException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("collection not found")) {
                return List.of();
            }
            throw ex;
        }
        List<VectorHit> hits = new ArrayList<>();
        JsonNode dataNode = root.path("data");
        if (dataNode.isArray()) {
            for (JsonNode item : dataNode) {
                long chunkId = item.path("chunk_id").asLong(0);
                if (chunkId == 0) {
                    chunkId = item.path("id").asLong(0);
                }
                if (chunkId == 0) {
                    continue;
                }
                hits.add(new VectorHit(chunkId, item.path("distance").asDouble(0)));
            }
        }
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return hits;
    }

    @Override
    public boolean healthy() {
        long now = System.currentTimeMillis();
        if (now - healthCheckedAt < HEALTH_CACHE_MILLIS) {
            return healthUp;
        }
        boolean up;
        try {
            up = isSuccess(post("/v2/vectordb/collections/list",
                    objectMapper.createObjectNode(), false));
        } catch (RuntimeException ex) {
            log.warn("Milvus health probe failed: {}", ex.getMessage());
            up = false;
        }
        healthUp = up;
        healthCheckedAt = now;
        return up;
    }

    private ObjectNode createBody(String collection, int dimension) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collectionName", collection);
        ObjectNode schema = body.putObject("schema");
        schema.put("autoId", false);
        schema.put("enabledDynamicField", false);
        ArrayNode fields = schema.putArray("fields");
        fields.addObject().put("fieldName", "chunk_id").put("dataType", "Int64").put("isPrimary", true);
        fields.addObject().put("fieldName", "doc_id").put("dataType", "Int64");
        ObjectNode spaceField = fields.addObject();
        spaceField.put("fieldName", "space_id").put("dataType", "Int64");
        if (sharedMode) {
            // Queries always filter by space first, so Milvus can prune to one partition bucket.
            spaceField.put("isPartitionKey", true);
        }
        fields.addObject().put("fieldName", "kb_id").put("dataType", "Int64");
        ObjectNode vectorField = fields.addObject();
        vectorField.put("fieldName", "embedding");
        vectorField.put("dataType", "FloatVector");
        vectorField.putObject("elementTypeParams").put("dim", String.valueOf(dimension));
        ObjectNode index = body.putArray("indexParams").addObject();
        index.put("fieldName", "embedding");
        index.put("indexName", "embedding_idx");
        index.put("metricType", "COSINE");
        index.putObject("params").put("index_type", "AUTOINDEX").put("metric_type", "COSINE");
        if (sharedMode) {
            body.putObject("params").put("partitionsNum", partitionsNum);
        }
        return body;
    }

    private void verifyDimension(String collection, int expected, JsonNode describe) {
        Integer actual = describedDimension(describe);
        if (actual != null && actual != expected) {
            throw new VectorStoreException("向量集合 " + collection + " 的维度为 " + actual
                    + "，与知识库配置的 " + expected + " 不一致，请重建向量索引");
        }
    }

    private void dropLegacyCollection(KnowledgeBase base, String activeCollection) {
        String legacy = Texts.isBlank(base.getVectorCollection()) ? "" : base.getVectorCollection().trim();
        if (legacy.isEmpty() || legacy.equals(activeCollection)) {
            return;
        }
        try {
            post("/v2/vectordb/collections/drop",
                    objectMapper.createObjectNode().put("collectionName", legacy), false);
            readyCollections.remove(legacy);
            log.info("Dropped legacy Milvus collection {}", legacy);
        } catch (VectorStoreException ex) {
            log.warn("Failed to drop legacy Milvus collection {}: {}", legacy, ex.getMessage());
        }
    }

    private JsonNode post(String path, ObjectNode body, boolean failOnError) {
        String operation = operationOf(path);
        long startedAt = System.nanoTime();
        String status = "success";
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (!Texts.isBlank(token)) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            JsonNode root = null;
            if (!text.isBlank()) {
                try {
                    root = objectMapper.readTree(text);
                } catch (Exception ex) {
                    status = failOnError ? "error" : "ignored";
                    if (failOnError) {
                        throw new VectorStoreException("Milvus 返回了无法解析的响应（HTTP "
                                + response.code() + "）");
                    }
                }
            }
            if (!response.isSuccessful() || !isSuccess(root)) {
                status = failOnError ? "error" : "ignored";
                if (failOnError) {
                    String message = root == null ? Texts.oneLine(text, 500)
                            : Texts.oneLine(root.path("message").asText(root.toString()), 500);
                    throw new VectorStoreException("Milvus 请求失败（HTTP " + response.code()
                            + "）：" + message);
                }
            }
            return root == null ? objectMapper.createObjectNode() : root;
        } catch (IOException ex) {
            status = "error";
            throw new VectorStoreException("Milvus 连接失败：" + ex.getMessage(), ex);
        } finally {
            metrics.milvusRequest(operation, status, System.nanoTime() - startedAt);
        }
    }

    private static String operationOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 || index == path.length() - 1 ? path : path.substring(index + 1);
    }

    private static boolean isSuccess(JsonNode root) {
        return root != null && root.has("code") && root.path("code").asInt(-1) == 0;
    }

    private static boolean isPerKbMode(String mode) {
        if (Texts.isBlank(mode)) {
            return false;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return "per-kb".equals(normalized) || "perkb".equals(normalized);
    }

    private static Integer describedDimension(JsonNode describe) {
        JsonNode data = describe.path("data");
        JsonNode collection = data.isArray() && !data.isEmpty() ? data.get(0) : data;
        JsonNode fields = collection.path("fields");
        if (!fields.isArray()) {
            return null;
        }
        for (JsonNode field : fields) {
            if (!"embedding".equals(field.path("fieldName").asText())) {
                continue;
            }
            String dim = field.path("elementTypeParams").path("dim").asText("");
            if (Texts.isBlank(dim)) {
                dim = field.path("dim").asText("");
            }
            try {
                return Integer.parseInt(dim.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String collectionName(KnowledgeBase base) {
        if (sharedMode) {
            return collectionPrefix + "_" + dimension(base);
        }
        if (Texts.isBlank(base.getVectorCollection())) {
            throw new VectorStoreException("知识库未配置向量集合");
        }
        return base.getVectorCollection().trim();
    }

    private static int dimension(KnowledgeBase base) {
        int dimension = base.getEmbeddingDim() == null ? 0 : base.getEmbeddingDim();
        if (dimension <= 0) {
            throw new VectorStoreException("知识库未配置向量维度");
        }
        return dimension;
    }

    /**
     * ACL 下推表达式：仅当拒绝列表足够小时才拼进过滤条件，避免超长 expr。
     * {@code doc_id} 是建索引时写入的标量字段；用 {@code !=} 链而非 {@code in}
     * 是为了兼容各版本 Milvus 表达式引擎的操作符集合。
     */
    private static String deniedFilter(java.util.Set<Long> deniedDocIds) {
        if (deniedDocIds == null || deniedDocIds.isEmpty() || deniedDocIds.size() > 256) {
            return "";
        }
        StringBuilder expr = new StringBuilder();
        for (Long docId : deniedDocIds) {
            if (docId == null) {
                continue;
            }
            expr.append(" && doc_id != ").append(docId);
        }
        return expr.toString();
    }

    private static String scopeFilter(KnowledgeBase base) {
        if (base.getSpaceId() == null) {
            throw new VectorStoreException("知识库缺少空间信息");
        }
        if (base.getId() == null) {
            throw new VectorStoreException("知识库缺少标识");
        }
        return "space_id == " + base.getSpaceId() + " && kb_id == " + base.getId();
    }
}
