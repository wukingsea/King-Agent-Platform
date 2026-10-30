package com.king.ai.common;

import com.king.ai.auth.RedisLoginAttemptGuard;
import com.king.ai.chat.RedisChatSessionLock;
import com.king.ai.chat.RedisPublicChatRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security enforcement points must reject with a retryable 503 when Redis is unavailable instead
 * of silently skipping the guard.
 */
class RedisResilienceTest {

    @Test
    void publicRateLimiterFailsClosed() {
        StringRedisTemplate redis = failingRedis();
        when(valueOps(redis).increment(anyString())).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> new RedisPublicChatRateLimiter(redis).check("ip|1.2.3.4", 10))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
    }

    @Test
    void loginAttemptGuardFailsClosed() {
        StringRedisTemplate redis = failingRedis();
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> new RedisLoginAttemptGuard(redis).check("alice", "1.2.3.4"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
    }

    @Test
    void sessionLockFailsClosed() {
        StringRedisTemplate redis = failingRedis();
        when(valueOps(redis).setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> new RedisChatSessionLock(redis).tryAcquire(7L, "owner"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
    }

    @Test
    void jobLockSkipsInsteadOfRunningOnEveryReplica() {
        StringRedisTemplate redis = failingRedis();
        when(valueOps(redis).setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("down"));

        assertThat(new RedisJobLock(redis).tryAcquire("audit-retention", "owner", Duration.ofMinutes(5)))
                .isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> valueOps(StringRedisTemplate redis) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return values;
    }

    private static StringRedisTemplate failingRedis() {
        return mock(StringRedisTemplate.class);
    }
}
