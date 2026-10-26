package com.king.ai.auth;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

public class RedisTokenStore implements TokenStore {

    private static final String TOKEN_PREFIX = "king:auth:token:";
    private static final String USER_PREFIX = "king:auth:user:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void save(String token, Long userId, Duration ignored) {
        redis.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(userId), ttl);
        String userKey = USER_PREFIX + userId;
        redis.opsForSet().add(userKey, token);
        redis.expire(userKey, ttl);
    }

    @Override
    public Long findUserId(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
        redis.expire(TOKEN_PREFIX + token, ttl);
        redis.expire(USER_PREFIX + userId, ttl);
        return userId;
    }

    @Override
    public void delete(String token) {
        Long userId = findUserId(token);
        redis.delete(TOKEN_PREFIX + token);
        if (userId != null) {
            redis.opsForSet().remove(USER_PREFIX + userId, token);
        }
    }

    @Override
    public void deleteByUserId(Long userId) {
        String userKey = USER_PREFIX + userId;
        Set<String> tokens = redis.opsForSet().members(userKey);
        if (tokens != null) {
            for (String token : tokens) {
                redis.delete(TOKEN_PREFIX + token);
            }
        }
        redis.delete(userKey);
    }
}
