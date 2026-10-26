package com.king.ai.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisChatSessionLock implements ChatSessionLock {

    private static final String KEY_PREFIX = "king:chat:lock:";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(Long sessionId, String owner) {
        if (sessionId == null || owner == null) {
            return true;
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(key(sessionId), owner, TTL);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void release(Long sessionId, String owner) {
        if (sessionId == null || owner == null) {
            return;
        }
        redis.execute(RELEASE_SCRIPT, List.of(key(sessionId)), owner);
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
