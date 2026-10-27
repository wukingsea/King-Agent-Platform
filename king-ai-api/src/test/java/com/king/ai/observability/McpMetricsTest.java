package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final McpMetrics metrics = new McpMetrics(registry);

    @Test
    void recordsCallsAndLatency() {
        metrics.call("weather", "forecast", false, 42);
        metrics.call("weather", "forecast", true, 80);

        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "error").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.mcp.latency")
                .tag("server", "weather").tag("tool", "forecast")
                .tag("status", "error").timer().count()).isEqualTo(1L);
    }

    @Test
    void capsServerCardinality() {
        for (int i = 0; i < McpMetrics.MAX_SERVERS + 5; i++) {
            metrics.call("server-" + i, "tool", false, 1);
        }

        assertThat(registry.get("king.ai.mcp.calls")
                .tag("server", "other").tag("tool", "tool")
                .tag("status", "success").counter().count())
                .isEqualTo(5.0);
    }
}
