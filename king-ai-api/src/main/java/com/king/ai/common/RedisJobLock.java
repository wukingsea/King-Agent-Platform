package com.king.ai.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisJobLock implements JobLock {

    private static final String KEY_PREFIX = "king:job:lock:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(String name, String owner, Duration ttl) {
        if (name == null || name.isBlank() || owner == null || owner.isBlank()
                || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return false;
        }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(KEY_PREFIX + name, owner, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            // Shared jobs cannot prove exclusivity without Redis, so skip this round instead of
            // letting every replica run the job concurrently.
            log.warn("Job lock unavailable for {}: {}", name, ex.getMessage());
            return false;
        }
    }

    @Override
    public void release(String name, String owner) {
        if (name == null || name.isBlank() || owner == null || owner.isBlank()) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + name), owner);
        } catch (RuntimeException ex) {
            RedisFailurePolicy.failOpen(log, "job lock release", ex);
        }
    }
}
