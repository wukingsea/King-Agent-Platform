package com.king.ai.observability;

import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChatMetrics {

    private static final int MAX_SPACE_GAUGES = 200;
    private static final String OTHER_SPACE = "other";

    private final MeterRegistry registry;
    private final int sseCapacity;
    private final Object spaceLock = new Object();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final AtomicInteger activeSse = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> activeBySpace = new ConcurrentHashMap<>();

    public ChatMetrics(MeterRegistry registry, KingAiProperties properties) {
        this.registry = registry;
        this.sseCapacity = properties.getChat().getSseCapacity();
        Gauge.builder("king.ai.chat.active", activeChats, AtomicInteger::get)
                .description("Number of chat generations currently running")
                .register(registry);
        Gauge.builder("king.ai.sse.connections", activeSse, AtomicInteger::get)
                .description("Number of SSE connections currently open")
                .register(registry);
        Gauge.builder("king.ai.sse.capacity", this, value -> value.sseCapacity)
                .description("Configured SSE connection capacity for this instance")
                .register(registry);
    }

    public void request(String source) {
        counter("king.ai.chat.requests", "source", safe(source)).increment();
    }

    public void rejected(String reason) {
        counter("king.ai.chat.rejected", "reason", safe(reason)).increment();
    }

    public void activeStarted(Long spaceId) {
        activeChats.incrementAndGet();
        spaceGauge(spaceId).incrementAndGet();
    }

    public void activeFinished(Long spaceId) {
        activeChats.updateAndGet(value -> Math.max(0, value - 1));
        AtomicInteger gauge = existingSpaceGauge(spaceId);
        if (gauge != null) {
            gauge.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public void toolCall(String source, boolean error) {
        counter("king.ai.chat.tool_calls",
                "source", safe(source),
                "result", error ? "error" : "success").increment();
    }

    public TokenTimer startTokenTimer(String model, String source) {
        return new TokenTimer(safe(model), safe(source));
    }

    public void finished(String source, String status, long durationMs, int promptTokens,
                         int completionTokens, String model) {
        String outcome = status == null ? "unknown" : status.toLowerCase(Locale.ROOT);
        registry.timer("king.ai.chat.duration",
                        "source", safe(source),
                        "status", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if (promptTokens > 0) {
            counter("king.ai.chat.tokens",
                    "type", "prompt", "model", safe(model)).increment(promptTokens);
        }
        if (completionTokens > 0) {
            counter("king.ai.chat.tokens",
                    "type", "completion", "model", safe(model)).increment(completionTokens);
        }
    }

    public boolean tryAcquireSse() {
        if (sseCapacity <= 0) {
            activeSse.incrementAndGet();
            return true;
        }
        while (true) {
            int current = activeSse.get();
            if (current >= sseCapacity) {
                return false;
            }
            if (activeSse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseSse() {
        activeSse.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void sseReconnect() {
        counter("king.ai.sse.reconnects").increment();
    }

    private AtomicInteger spaceGauge(Long spaceId) {
        String key = spaceKey(spaceId);
        AtomicInteger gauge = activeBySpace.get(key);
        if (gauge != null) {
            return gauge;
        }
        synchronized (spaceLock) {
            gauge = activeBySpace.get(key);
            if (gauge != null) {
                return gauge;
            }
            String bucket = activeBySpace.size() < MAX_SPACE_GAUGES - 1 ? key : OTHER_SPACE;
            AtomicInteger created = activeBySpace.get(bucket);
            if (created == null) {
                created = registerSpaceGauge(bucket);
                activeBySpace.put(bucket, created);
            }
            return created;
        }
    }

    private AtomicInteger existingSpaceGauge(Long spaceId) {
        String key = spaceKey(spaceId);
        AtomicInteger exact = activeBySpace.get(key);
        if (exact != null) {
            return exact;
        }
        return activeBySpace.size() >= MAX_SPACE_GAUGES - 1 ? activeBySpace.get(OTHER_SPACE) : null;
    }

    private AtomicInteger registerSpaceGauge(String space) {
        AtomicInteger value = new AtomicInteger();
        Gauge.builder("king.ai.chat.active.space", value, AtomicInteger::get)
                .tag("space", space)
                .description("Active chat generations by space (199 real spaces plus other)")
                .register(registry);
        return value;
    }

    private String spaceKey(Long spaceId) {
        return spaceId == null ? "unknown" : String.valueOf(spaceId);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 100);
    }

    public final class TokenTimer {

        private final String model;
        private final String source;
        private final AtomicBoolean recorded = new AtomicBoolean();
        private volatile long startedAt = System.nanoTime();

        private TokenTimer(String model, String source) {
            this.model = model;
            this.source = source;
        }

        public void reset() {
            startedAt = System.nanoTime();
            recorded.set(false);
        }

        public void firstToken() {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            Timer.builder("king.ai.chat.ttft")
                    .tag("model", model)
                    .tag("source", source)
                    .description("Time from LLM request start to first streamed token")
                    .register(registry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
