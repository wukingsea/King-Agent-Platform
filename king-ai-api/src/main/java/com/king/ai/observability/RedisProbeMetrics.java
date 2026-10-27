package com.king.ai.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "king-ai", name = "token-store", havingValue = "redis",
        matchIfMissing = true)
public class RedisProbeMetrics {

    private final ObjectProvider<RedisConnectionFactory> factories;
    private final MeterRegistry registry;
    private final AtomicInteger up = new AtomicInteger();

    public RedisProbeMetrics(ObjectProvider<RedisConnectionFactory> factories, MeterRegistry registry) {
        this.factories = factories;
        this.registry = registry;
        Gauge.builder("king.ai.redis.up", up, AtomicInteger::get)
                .description("Redis reachability from the last probe (1 up, 0 down)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${king-ai.redis.probe-interval-ms:30000}")
    public void probe() {
        long startedAt = System.nanoTime();
        String status = "error";
        RedisConnection connection = null;
        try {
            RedisConnectionFactory factory = factories.getIfAvailable();
            if (factory == null) {
                up.set(0);
                return;
            }
            connection = factory.getConnection();
            String pong = connection.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                up.set(1);
                status = "success";
            } else {
                up.set(0);
            }
        } catch (RuntimeException ex) {
            up.set(0);
            log.debug("Redis probe failed: {}", ex.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                }
            }
            registry.counter("king.ai.redis.probes", "status", status).increment();
            registry.timer("king.ai.redis.probe.latency", "status", status)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
