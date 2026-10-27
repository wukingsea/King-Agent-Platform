package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisProbeMetricsTest {

    @Test
    void recordsSuccessfulProbe() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics(factory, registry).probe();

        assertThat(registry.get("king.ai.redis.up").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probes").tag("status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probe.latency").tag("status", "success")
                .timer().count()).isEqualTo(1L);
        verify(connection).close();
    }

    @Test
    void recordsFailureWhenRedisIsDown() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RuntimeException("redis down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics(factory, registry).probe();

        assertThat(registry.get("king.ai.redis.up").gauge().value()).isZero();
        assertThat(registry.get("king.ai.redis.probes").tag("status", "error")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.redis.probe.latency").tag("status", "error")
                .timer().count()).isEqualTo(1L);
    }

    private RedisProbeMetrics metrics(RedisConnectionFactory factory, SimpleMeterRegistry registry) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("redisConnectionFactory", factory);
        return new RedisProbeMetrics(beans.getBeanProvider(RedisConnectionFactory.class), registry);
    }
}
