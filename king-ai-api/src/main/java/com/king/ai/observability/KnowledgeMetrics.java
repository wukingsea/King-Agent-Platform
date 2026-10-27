package com.king.ai.observability;

import com.king.ai.common.Texts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class KnowledgeMetrics {

    private static final String INDEX_JOBS = "king.ai.index.jobs";
    private static final String INDEX_DURATION = "king.ai.index.duration";
    private static final String RAG_RETRIEVAL = "king.ai.rag.retrieval";
    private static final String MILVUS_REQUESTS = "king.ai.milvus.requests";
    private static final String MILVUS_LATENCY = "king.ai.milvus.latency";

    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicReference<LocalDateTime> oldestPendingAt = new AtomicReference<>();

    public KnowledgeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("king.ai.index.pending", pending, AtomicLong::get)
                .description("Knowledge index jobs waiting to run")
                .register(registry);
        Gauge.builder("king.ai.index.failed", failed, AtomicLong::get)
                .description("Knowledge index jobs in the failed state")
                .register(registry);
        Gauge.builder("king.ai.index.lag.seconds", oldestPendingAt, value -> {
                    LocalDateTime oldest = value.get();
                    return oldest == null ? 0.0
                            : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).toSeconds());
                })
                .description("Age in seconds of the oldest pending knowledge index job")
                .register(registry);
    }

    public void indexJob(String outcome, long elapsedNanos) {
        String value = safe(outcome);
        if ("skipped".equals(value)) {
            return;
        }
        registry.counter(INDEX_JOBS, "outcome", value).increment();
        registry.timer(INDEX_DURATION, "outcome", value).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void updateQueue(long pendingCount, long failedCount, LocalDateTime oldestPending) {
        pending.set(Math.max(0L, pendingCount));
        failed.set(Math.max(0L, failedCount));
        oldestPendingAt.set(oldestPending);
    }

    public void ragRetrieval(long elapsedNanos, boolean empty, boolean failed) {
        String status = failed ? "error" : empty ? "empty" : "success";
        registry.timer(RAG_RETRIEVAL, "status", status).record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (empty && !failed) {
            registry.counter("king.ai.rag.empty").increment();
        }
    }

    public void milvusRequest(String operation, String status, long elapsedNanos) {
        String op = safe(operation);
        String outcome = safe(status);
        registry.counter(MILVUS_REQUESTS, "operation", op, "status", outcome).increment();
        registry.timer(MILVUS_LATENCY, "operation", op, "status", outcome)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private static String safe(String value) {
        return Texts.isBlank(value) ? "unknown" : Texts.trimTo(value.trim(), 80);
    }
}
