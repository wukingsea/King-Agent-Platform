package com.king.ai.chat;

import com.king.ai.common.Ids;
import com.king.ai.llm.CancellationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisChatCancellationRegistry implements ChatCancellationRegistry {

    public static final String CANCEL_CHANNEL = "king:chat:cancel";
    private static final String KEY_PREFIX = "king:chat:request:";
    private static final Duration METADATA_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final Map<String, Entry> local = new ConcurrentHashMap<>();

    @Override
    public String register(CancellationToken token, Long spaceId, String actorKey) {
        String requestId = Ids.publicId();
        local.put(requestId, new Entry(token, spaceId, actorKey));
        log.debug("Registered chat request: requestId={}, spaceId={}, actor={}", requestId, spaceId, actorKey);
        try {
            redis.opsForValue().set(key(requestId), encode(spaceId, actorKey), METADATA_TTL);
        } catch (RuntimeException ex) {
            log.warn("Failed to store chat request metadata: {}", ex.getMessage());
        }
        return requestId;
    }

    @Override
    public boolean cancel(String requestId, Long spaceId, String actorKey, boolean canManage) {
        if (requestId == null) {
            return false;
        }
        Entry entry = local.get(requestId);
        Long storedSpace = entry == null ? null : entry.spaceId();
        String storedActor = entry == null ? null : entry.actorKey();
        try {
            String metadata = redis.opsForValue().get(key(requestId));
            if (metadata != null) {
                int separator = metadata.indexOf('|');
                if (separator > 0) {
                    String spacePart = metadata.substring(0, separator);
                    storedSpace = "-".equals(spacePart) ? null : Long.valueOf(spacePart);
                    storedActor = metadata.substring(separator + 1);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to read chat request metadata: {}", ex.getMessage());
        }
        if (storedActor == null) {
            return false;
        }
        if (spaceId != null && !Objects.equals(storedSpace, spaceId)) {
            return false;
        }
        if (!canManage && !Objects.equals(storedActor, actorKey)) {
            return false;
        }
        try {
            redis.convertAndSend(CANCEL_CHANNEL, requestId);
        } catch (RuntimeException ex) {
            log.warn("Failed to broadcast chat cancellation: {}", ex.getMessage());
        }
        cancelLocal(requestId);
        return true;
    }

    public void cancelLocal(String requestId) {
        if (requestId == null) {
            return;
        }
        Entry entry = local.get(requestId);
        if (entry != null) {
            entry.token().cancel();
            log.debug("Cancelled local chat request: requestId={}", requestId);
        } else {
            log.debug("Chat request not owned by this instance: requestId={}", requestId);
        }
    }

    @Override
    public void remove(String requestId) {
        if (requestId == null) {
            return;
        }
        local.remove(requestId);
        try {
            redis.delete(key(requestId));
        } catch (RuntimeException ex) {
            log.warn("Failed to remove chat request metadata: {}", ex.getMessage());
        }
    }

    private String key(String requestId) {
        return KEY_PREFIX + requestId;
    }

    private String encode(Long spaceId, String actorKey) {
        return (spaceId == null ? "-" : spaceId.toString()) + "|" + actorKey;
    }

    private record Entry(CancellationToken token, Long spaceId, String actorKey) {
    }
}
