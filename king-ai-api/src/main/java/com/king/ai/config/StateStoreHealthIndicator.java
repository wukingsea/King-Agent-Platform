package com.king.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component("stateStore")
@RequiredArgsConstructor
public class StateStoreHealthIndicator implements HealthIndicator {

    private final KingAiProperties properties;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;

    @Override
    public Health health() {
        if (!"redis".equalsIgnoreCase(properties.getTokenStore())) {
            return Health.up().withDetail("store", properties.getTokenStore()).build();
        }
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            return Health.down().withDetail("store", "redis")
                    .withDetail("error", "RedisConnectionFactory is not configured").build();
        }
        RedisConnection connection = null;
        try {
            connection = factory.getConnection();
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up().withDetail("store", "redis").build();
            }
            return Health.down().withDetail("store", "redis").withDetail("ping", pong).build();
        } catch (RuntimeException ex) {
            return Health.down().withDetail("store", "redis")
                    .withDetail("error", ex.getClass().getSimpleName()).build();
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
