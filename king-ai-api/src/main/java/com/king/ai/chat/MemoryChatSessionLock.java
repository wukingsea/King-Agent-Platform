package com.king.ai.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryChatSessionLock implements ChatSessionLock {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final Map<Long, Entry> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(Long sessionId, String owner) {
        if (sessionId == null || owner == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Entry result = locks.compute(sessionId, (key, current) -> {
            if (current == null || current.expiresAt() <= now) {
                return new Entry(owner, now + TTL.toMillis());
            }
            return current;
        });
        return Objects.equals(result.owner(), owner);
    }

    @Override
    public void release(Long sessionId, String owner) {
        if (sessionId == null || owner == null) {
            return;
        }
        locks.computeIfPresent(sessionId, (key, current) ->
                Objects.equals(current.owner(), owner) ? null : current);
    }

    private record Entry(String owner, long expiresAt) {
    }
}
