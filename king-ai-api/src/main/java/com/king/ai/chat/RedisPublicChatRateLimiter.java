package com.king.ai.chat;

import com.king.ai.common.BizException;
import com.king.ai.common.RedisFailurePolicy;
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
public class RedisPublicChatRateLimiter implements PublicChatRateLimiter {

    private static final String KEY_PREFIX = "king:chat:rate:";
    private static final Duration EXPIRY = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;

    @Override
    public void check(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60000L;
        String redisKey = KEY_PREFIX + minute + ":" + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, EXPIRY);
            }
            if (count != null && count > limitPerMinute) {
                throw BizException.tooManyRequests("请求过于频繁，请稍后再试");
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Abuse protection must not disappear during a Redis outage.
            throw RedisFailurePolicy.failClosed("public chat rate limiter", ex);
        }
    }
}
