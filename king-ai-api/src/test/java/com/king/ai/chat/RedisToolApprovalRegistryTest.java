package com.king.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisToolApprovalRegistryTest {

    private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();
    private final List<String> published = new CopyOnWriteArrayList<>();
    private final StringRedisTemplate redis = fakeRedis(hashes, published);

    @Test
    void decisionOnAnotherReplicaCompletesTheOwnersPendingCall() {
        RedisToolApprovalRegistry owner = new RedisToolApprovalRegistry(redis);
        RedisToolApprovalRegistry other = new RedisToolApprovalRegistry(redis);

        CompletableFuture<Boolean> decision = owner.register("req-1", "call-1", 7L, "user:42",
                "mcp__github__create_issue", "MCP", "{}");

        ToolApprovalRegistry.Pending pending = other.decide(
                "req-1", "call-1", 7L, "user:42", false, true);

        assertThat(pending).isNotNull();
        assertThat(pending.toolName()).isEqualTo("mcp__github__create_issue");
        assertThat(pending.source()).isEqualTo("MCP");
        assertThat(decision).isNotDone();
        assertThat(published).hasSize(1);

        owner.onDecision(published.get(0));

        assertThat(decision).isCompletedWithValue(true);
    }

    @Test
    void onlyTheFirstDecisionWinsAcrossReplicas() {
        RedisToolApprovalRegistry first = new RedisToolApprovalRegistry(redis);
        RedisToolApprovalRegistry second = new RedisToolApprovalRegistry(redis);
        first.register("req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(first.decide("req-1", "call-1", 7L, "user:42", false, true)).isNotNull();
        assertThat(second.decide("req-1", "call-1", 7L, "user:42", false, false)).isNull();
    }

    @Test
    void actorFromAnotherSpaceCannotDecideUsingSharedMetadata() {
        RedisToolApprovalRegistry owner = new RedisToolApprovalRegistry(redis);
        RedisToolApprovalRegistry other = new RedisToolApprovalRegistry(redis);
        CompletableFuture<Boolean> decision = owner.register(
                "req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(other.decide("req-1", "call-1", 8L, "user:42", true, true)).isNull();
        assertThat(other.decide("req-1", "call-1", 7L, "user:99", false, true)).isNull();
        assertThat(decision).isNotDone();
    }

    @Test
    void redisOutageFallsBackToLocalDecision() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForHash()).thenThrow(new IllegalStateException("redis down"));
        when(broken.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("redis down"));
        RedisToolApprovalRegistry registry = new RedisToolApprovalRegistry(broken);

        CompletableFuture<Boolean> decision = registry.register(
                "req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");
        ToolApprovalRegistry.Pending pending = registry.decide(
                "req-1", "call-1", 7L, "user:42", false, true);

        assertThat(pending).isNotNull();
        assertThat(decision).isCompletedWithValue(true);
    }

    @Test
    void removingARequestReleasesLocalPendingCalls() {
        RedisToolApprovalRegistry registry = new RedisToolApprovalRegistry(redis);
        registry.register("req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        registry.removeRequest("req-1");

        assertThat(hashes).isEmpty();
        assertThat(registry.decide("req-1", "call-1", 7L, "user:42", false, true)).isNull();
    }

    @SuppressWarnings("unchecked")
    static StringRedisTemplate fakeRedis(Map<String, Map<String, String>> hashes, List<String> published) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);
        doAnswer(invocation -> {
            hashes.computeIfAbsent(invocation.getArgument(0), key -> new ConcurrentHashMap<>())
                    .putAll((Map<String, String>) invocation.getArgument(1));
            return null;
        }).when(hashOps).putAll(anyString(), anyMap());
        when(hashOps.entries(anyString())).thenAnswer(invocation ->
                new HashMap<String, Object>(hashes.getOrDefault(invocation.getArgument(0), Map.of())));
        when(hashOps.putIfAbsent(anyString(), any(), any())).thenAnswer(invocation -> {
            Map<String, String> hash = hashes.computeIfAbsent(
                    invocation.getArgument(0), key -> new ConcurrentHashMap<>());
            return hash.putIfAbsent((String) invocation.getArgument(1), (String) invocation.getArgument(2)) == null;
        });
        when(template.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(template.delete(anyString())).thenAnswer(invocation -> {
            hashes.remove(invocation.<String>getArgument(0));
            return true;
        });
        when(template.convertAndSend(anyString(), anyString())).thenAnswer(invocation -> {
            published.add(invocation.getArgument(1));
            return 1L;
        });
        return template;
    }
}
