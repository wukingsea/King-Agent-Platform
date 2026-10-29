package com.king.ai.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryJobLock implements JobLock {

    private final Map<String, Entry> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public MemoryJobLock() {
        this(Clock.systemUTC());
    }

    MemoryJobLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String name, String owner, Duration ttl) {
        if (isBlank(name) || isBlank(owner) || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return false;
        }
        long now = clock.millis();
        long expiresAt = now + ttl.toMillis();
        Entry result = locks.compute(name, (key, current) ->
                current == null || current.expiresAt() <= now
                        ? new Entry(owner, expiresAt)
                        : current);
        return Objects.equals(result.owner(), owner);
    }

    @Override
    public void release(String name, String owner) {
        if (isBlank(name) || isBlank(owner)) {
            return;
        }
        locks.computeIfPresent(name, (key, current) ->
                Objects.equals(current.owner(), owner) ? null : current);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Entry(String owner, long expiresAt) {
    }
}
