package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisLoginAttemptGuard implements LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "king:auth:fail:";

    private final StringRedisTemplate redis;

    @Override
    public void check(String username, String clientIp) {
        try {
            String value = redis.opsForValue().get(key(username, clientIp));
            if (value != null && parse(value) >= MAX_FAILURES) {
                throw BizException.tooManyRequests("登录失败次数过多，请 5 分钟后再试");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Login attempt guard unavailable: {}", ex.getMessage());
        }
    }

    @Override
    public void fail(String username, String clientIp) {
        try {
            String key = key(username, clientIp);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to record login failure: {}", ex.getMessage());
        }
    }

    @Override
    public void success(String username, String clientIp) {
        try {
            redis.delete(key(username, clientIp));
        } catch (RuntimeException ex) {
            log.warn("Failed to clear login failures: {}", ex.getMessage());
        }
    }

    private long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String key(String username, String clientIp) {
        return KEY_PREFIX + normalize(username) + ":" + normalize(clientIp);
    }

    private String normalize(String value) {
        return Texts.isBlank(value) ? "unknown" : value.trim().toLowerCase();
    }
}
