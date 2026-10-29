package com.king.ai.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-replica approval registry. The approval HTTP request may land on any instance, so the
 * pending call metadata and the outcome live in Redis and the decision is broadcast back to the
 * replica that owns the SSE stream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisToolApprovalRegistry implements ToolApprovalRegistry {

    public static final String DECISION_CHANNEL = "king:chat:approval";
    private static final String KEY_PREFIX = "king:chat:approval:";
    private static final String DECISION_FIELD = "decision";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final Map<String, LocalCall> local = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Boolean> register(String requestId, String callId, Long spaceId,
                                               String actorKey, String toolName, String source,
                                               String arguments) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        local.put(compositeKey(requestId, callId),
                new LocalCall(spaceId, actorKey, toolName, source, arguments, decision));
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("spaceId", spaceId == null ? "-" : spaceId.toString());
            metadata.put("actorKey", actorKey == null ? "" : actorKey);
            metadata.put("toolName", toolName == null ? "" : toolName);
            metadata.put("source", source == null ? "" : source);
            metadata.put("arguments", arguments == null ? "" : arguments);
            String key = key(requestId, callId);
            redis.opsForHash().putAll(key, metadata);
            redis.expire(key, TTL);
        } catch (RuntimeException ex) {
            log.warn("Failed to share tool approval state: {}", ex.getMessage());
        }
        return decision;
    }

    @Override
    public Pending decide(String requestId, String callId, Long spaceId, String actorKey,
                          boolean canManage, boolean approved) {
        LocalCall localCall = local.get(compositeKey(requestId, callId));
        Map<Object, Object> stored = readMetadata(requestId, callId);
        if (stored == null || stored.isEmpty()) {
            return decideLocally(localCall, spaceId, actorKey, canManage, approved);
        }
        Long storedSpace = parseSpace(stored.get("spaceId"));
        String storedActor = string(stored.get("actorKey"));
        if (!allowed(storedSpace, storedActor, spaceId, actorKey, canManage)) {
            return null;
        }
        try {
            Boolean fresh = redis.opsForHash().putIfAbsent(key(requestId, callId), DECISION_FIELD,
                    approved ? "APPROVED" : "DENIED");
            if (!Boolean.TRUE.equals(fresh)) {
                return null;
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to claim tool approval decision: {}", ex.getMessage());
            if (localCall == null) {
                return null;
            }
        }
        if (localCall != null) {
            localCall.decision().complete(approved);
        }
        publish(requestId, callId, approved);
        return new Pending(storedSpace, storedActor, string(stored.get("toolName")),
                string(stored.get("source")), string(stored.get("arguments")));
    }

    @Override
    public void remove(String requestId, String callId) {
        local.remove(compositeKey(requestId, callId));
        try {
            redis.delete(key(requestId, callId));
        } catch (RuntimeException ex) {
            log.warn("Failed to remove tool approval state: {}", ex.getMessage());
        }
    }

    @Override
    public void removeRequest(String requestId) {
        if (requestId == null) {
            return;
        }
        String prefix = requestId + "|";
        for (String composite : List.copyOf(local.keySet())) {
            if (!composite.startsWith(prefix)) {
                continue;
            }
            local.remove(composite);
            try {
                redis.delete(key(requestId, composite.substring(prefix.length())));
            } catch (RuntimeException ex) {
                log.warn("Failed to remove tool approval state: {}", ex.getMessage());
            }
        }
    }

    /**
     * Completes the local future after a decision broadcast from another replica.
     */
    public void onDecision(String payload) {
        if (payload == null) {
            return;
        }
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) {
            log.warn("Ignoring malformed tool approval notification");
            return;
        }
        LocalCall call = local.get(compositeKey(parts[0], parts[1]));
        if (call != null) {
            call.decision().complete("1".equals(parts[2]));
        }
    }

    private Pending decideLocally(LocalCall call, Long spaceId, String actorKey,
                                  boolean canManage, boolean approved) {
        if (call == null
                || !allowed(call.spaceId(), call.actorKey(), spaceId, actorKey, canManage)) {
            return null;
        }
        return call.decision().complete(approved) ? call.metadata() : null;
    }

    private Map<Object, Object> readMetadata(String requestId, String callId) {
        try {
            return redis.opsForHash().entries(key(requestId, callId));
        } catch (RuntimeException ex) {
            log.warn("Failed to read tool approval state: {}", ex.getMessage());
            return null;
        }
    }

    private void publish(String requestId, String callId, boolean approved) {
        try {
            redis.convertAndSend(DECISION_CHANNEL, requestId + "|" + callId + "|" + (approved ? "1" : "0"));
        } catch (RuntimeException ex) {
            log.warn("Failed to broadcast tool approval: {}", ex.getMessage());
        }
    }

    private static boolean allowed(Long storedSpace, String storedActor, Long spaceId,
                                   String actorKey, boolean canManage) {
        if (spaceId != null && !Objects.equals(storedSpace, spaceId)) {
            return false;
        }
        return canManage || Objects.equals(storedActor, actorKey);
    }

    private static String compositeKey(String requestId, String callId) {
        return requestId + "|" + callId;
    }

    private static String key(String requestId, String callId) {
        return KEY_PREFIX + requestId + ":" + callId;
    }

    private static Long parseSpace(Object value) {
        String text = string(value);
        return text == null || "-".equals(text) || text.isEmpty() ? null : Long.valueOf(text);
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private record LocalCall(Long spaceId, String actorKey, String toolName, String source,
                             String arguments, CompletableFuture<Boolean> decision) {

        ToolApprovalRegistry.Pending metadata() {
            return new ToolApprovalRegistry.Pending(spaceId, actorKey, toolName, source, arguments);
        }
    }
}
