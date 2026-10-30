package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.common.RedisFailurePolicy;
import com.king.ai.common.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisLoginAttemptGuard implements LoginAttemptGuard {

    private static final String FAIL_PREFIX = "king:auth:fail:";
    private static final String LOCK_PREFIX = "king:auth:lock:";

    private final StringRedisTemplate redis;

    @Override
    public void check(String username, String clientIp) {
        try {
            String user = normalize(username);
            String ip = normalize(clientIp);
            ensureUnlocked(LOCK_PREFIX + "pair:" + user + ":" + ip, LoginFailurePolicy::pairMessage);
            ensureUnlocked(LOCK_PREFIX + "account:" + user, LoginFailurePolicy::accountMessage);
            ensureUnlocked(LOCK_PREFIX + "ip:" + ip, LoginFailurePolicy::ipMessage);
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Credential-stuffing protection must not disappear during a Redis outage.
            throw RedisFailurePolicy.failClosed("login attempt guard", ex);
        }
    }

    @Override
    public void fail(String username, String clientIp) {
        try {
            String user = normalize(username);
            String ip = normalize(clientIp);
            String pairKey = "pair:" + user + ":" + ip;
            long pairFailures = increment(FAIL_PREFIX + pairKey, LoginFailurePolicy.PAIR_WINDOW);
            long accountFailures = increment(FAIL_PREFIX + "account:" + user,
                    LoginFailurePolicy.ACCOUNT_WINDOW);
            long ipFailures = increment(FAIL_PREFIX + "ip:" + ip, LoginFailurePolicy.IP_WINDOW);

            Duration pairLock = LoginFailurePolicy.lockDuration((int) pairFailures);
            if (!pairLock.isZero()) {
                lock(LOCK_PREFIX + pairKey, pairLock);
            }
            if (accountFailures >= LoginFailurePolicy.ACCOUNT_THRESHOLD) {
                lock(LOCK_PREFIX + "account:" + user, LoginFailurePolicy.ACCOUNT_LOCK);
            }
            if (ipFailures >= LoginFailurePolicy.IP_THRESHOLD) {
                lock(LOCK_PREFIX + "ip:" + ip, LoginFailurePolicy.IP_LOCK);
            }
        } catch (RuntimeException ex) {
            RedisFailurePolicy.failOpen(log, "login failure accounting", ex);
        }
    }

    @Override
    public void success(String username, String clientIp) {
        try {
            String pairKey = "pair:" + normalize(username) + ":" + normalize(clientIp);
            redis.delete(FAIL_PREFIX + pairKey);
            redis.delete(LOCK_PREFIX + pairKey);
        } catch (RuntimeException ex) {
            RedisFailurePolicy.failOpen(log, "login failure reset", ex);
        }
    }

    private void ensureUnlocked(String key, LongFunction<String> message) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw BizException.tooManyRequests(message.apply(ttl));
        }
    }

    private long increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null ? 0 : count;
    }

    private void lock(String key, Duration duration) {
        redis.opsForValue().set(key, "1", duration);
    }

    private String normalize(String value) {
        return Texts.isBlank(value) ? "unknown" : value.trim().toLowerCase();
    }
}
