package com.king.ai.auth;

import com.king.ai.auth.dto.LoginSessionView;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RedisTokenStore implements TokenStore {

    private static final String TOKEN_PREFIX = "king:auth:token:";
    private static final String USER_PREFIX = "king:auth:user:";
    private static final String SESSION_PREFIX = "king:auth:session:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void save(String token, Long userId, Duration ignored, String clientIp, String userAgent) {
        long now = System.currentTimeMillis();
        redis.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(userId), ttl);
        String userKey = USER_PREFIX + userId;
        redis.opsForSet().add(userKey, token);
        redis.expire(userKey, ttl);

        String sessionKey = SESSION_PREFIX + TokenStore.sessionId(token);
        Map<String, String> meta = new HashMap<>();
        meta.put("userId", String.valueOf(userId));
        meta.put("clientIp", clientIp == null ? "" : clientIp);
        meta.put("userAgent", userAgent == null ? "" : userAgent);
        meta.put("createdAt", String.valueOf(now));
        redis.opsForHash().putAll(sessionKey, meta);
        redis.expire(sessionKey, ttl);
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
        redis.expire(SESSION_PREFIX + TokenStore.sessionId(token), ttl);
        return userId;
    }

    @Override
    public void delete(String token) {
        Long userId = findUserId(token);
        redis.delete(TOKEN_PREFIX + token);
        redis.delete(SESSION_PREFIX + TokenStore.sessionId(token));
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
                deleteToken(token);
            }
        }
        redis.delete(userKey);
    }

    @Override
    public List<LoginSessionView> sessions(Long userId, String currentToken) {
        Set<String> tokens = redis.opsForSet().members(USER_PREFIX + userId);
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        String currentId = currentToken == null ? null : TokenStore.sessionId(currentToken);
        long now = System.currentTimeMillis();
        List<LoginSessionView> result = new ArrayList<>();
        for (String token : tokens) {
            String id = TokenStore.sessionId(token);
            Map<Object, Object> meta = redis.opsForHash().entries(SESSION_PREFIX + id);
            Long remaining = redis.getExpire(TOKEN_PREFIX + token, TimeUnit.MILLISECONDS);
            if (remaining == null || remaining <= 0) {
                redis.opsForSet().remove(USER_PREFIX + userId, token);
                redis.delete(SESSION_PREFIX + id);
                continue;
            }
            long createdAt = parseLong(meta.get("createdAt"), now);
            result.add(new LoginSessionView(id,
                    meta.get("clientIp") == null ? "" : String.valueOf(meta.get("clientIp")),
                    meta.get("userAgent") == null ? "" : String.valueOf(meta.get("userAgent")),
                    toLocal(createdAt), toLocal(now + remaining), id.equals(currentId)));
        }
        result.sort(Comparator.comparing(LoginSessionView::createdAt).reversed());
        return result;
    }

    @Override
    public int deleteOthers(Long userId, String currentToken) {
        Set<String> tokens = redis.opsForSet().members(USER_PREFIX + userId);
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        String currentId = currentToken == null ? null : TokenStore.sessionId(currentToken);
        int revoked = 0;
        for (String token : tokens) {
            if (TokenStore.sessionId(token).equals(currentId)) {
                continue;
            }
            deleteToken(token);
            redis.opsForSet().remove(USER_PREFIX + userId, token);
            revoked++;
        }
        return revoked;
    }

    private void deleteToken(String token) {
        redis.delete(TOKEN_PREFIX + token);
        redis.delete(SESSION_PREFIX + TokenStore.sessionId(token));
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static LocalDateTime toLocal(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
