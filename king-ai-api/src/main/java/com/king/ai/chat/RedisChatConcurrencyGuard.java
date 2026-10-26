package com.king.ai.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Distributed per-space concurrency guard backed by a Redis sorted set. Stale members are
 * pruned by score so a crashed instance cannot permanently consume another tenant's slots.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisChatConcurrencyGuard implements ChatConcurrencyGuard {

    private static final String KEY_PREFIX = "king:chat:concurrency:";
    /**
     * Longer than the maximum SSE lifetime so a healthy long-running stream is never evicted,
     * while members from crashed instances expire automatically.
     */
    private static final long TTL_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long TTL_SECONDS = Duration.ofMinutes(15).toSeconds();

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local cutoff = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local ttl = tonumber(ARGV[5])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            if redis.call('ZSCORE', key, member) then
                redis.call('ZADD', key, now, member)
                redis.call('EXPIRE', key, ttl)
                return 1
            end
            if redis.call('ZCARD', key) >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, ttl)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(Long spaceId, String requestId, int limit) {
        if (spaceId == null || requestId == null || limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        try {
            Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(spaceId)),
                    String.valueOf(now), String.valueOf(now - TTL_MILLIS),
                    String.valueOf(limit), requestId, String.valueOf(TTL_SECONDS));
            return result == null || result == 1L;
        } catch (RuntimeException ex) {
            // Quota enforcement is a protection, not an authorization boundary: fail open rather
            // than taking chat down when Redis is unavailable.
            log.warn("Failed to acquire chat concurrency slot for space {}: {}", spaceId, ex.getMessage());
            return true;
        }
    }

    @Override
    public void release(Long spaceId, String requestId) {
        if (spaceId == null || requestId == null) {
            return;
        }
        try {
            redis.opsForZSet().remove(key(spaceId), requestId);
        } catch (RuntimeException ex) {
            log.warn("Failed to release chat concurrency slot {}: {}", requestId, ex.getMessage());
        }
    }

    private String key(Long spaceId) {
        return KEY_PREFIX + spaceId;
    }
}
