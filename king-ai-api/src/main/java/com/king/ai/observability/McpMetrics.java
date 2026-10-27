package com.king.ai.observability;

import com.king.ai.common.Texts;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class McpMetrics {

    static final int MAX_SERVERS = 100;

    private final MeterRegistry registry;
    private final Set<String> servers = ConcurrentHashMap.newKeySet();

    public McpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void call(String server, String tool, boolean error, long durationMs) {
        String serverTag = serverTag(server);
        String toolTag = safe(tool);
        String status = error ? "error" : "success";
        registry.counter("king.ai.mcp.calls",
                "server", serverTag, "tool", toolTag, "status", status).increment();
        registry.timer("king.ai.mcp.latency",
                        "server", serverTag, "tool", toolTag, "status", status)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private synchronized String serverTag(String server) {
        if (Texts.isBlank(server)) {
            return "unknown";
        }
        String value = Texts.trimTo(server.trim(), 80);
        if (servers.contains(value)) {
            return value;
        }
        if (servers.size() >= MAX_SERVERS) {
            return "other";
        }
        servers.add(value);
        return value;
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 80);
    }
}
