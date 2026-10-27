package com.king.ai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KnowledgeMetrics metrics = new KnowledgeMetrics(registry);

    @Test
    void recordsIndexJobsButSkipsNoopOutcome() {
        metrics.indexJob("succeeded", 1_000_000L);
        metrics.indexJob("skipped", 1_000_000L);

        assertThat(registry.get("king.ai.index.jobs").tag("outcome", "succeeded")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("king.ai.index.jobs").tag("outcome", "skipped").counter())
                .isNull();
        assertThat(registry.get("king.ai.index.duration").tag("outcome", "succeeded")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void exposesQueueBacklogAndLag() {
        metrics.updateQueue(3, 1, LocalDateTime.now().minusMinutes(5));

        assertThat(registry.get("king.ai.index.pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("king.ai.index.failed").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("king.ai.index.lag.seconds").gauge().value())
                .isBetween(290.0, 310.0);

        metrics.updateQueue(0, 0, null);
        assertThat(registry.get("king.ai.index.lag.seconds").gauge().value()).isZero();
    }

    @Test
    void recordsRagOutcomesAndEmptyResults() {
        metrics.ragRetrieval(1_000_000L, false, false);
        metrics.ragRetrieval(2_000_000L, true, false);
        metrics.ragRetrieval(3_000_000L, false, true);

        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "success")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "empty")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.retrieval").tag("status", "error")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("king.ai.rag.empty").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsMilvusRequestsAndLatency() {
        metrics.milvusRequest("search", "success", 1_000_000L);
        metrics.milvusRequest("search", "error", 2_000_000L);

        assertThat(registry.get("king.ai.milvus.requests")
                .tag("operation", "search").tag("status", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("king.ai.milvus.latency")
                .tag("operation", "search").tag("status", "error").timer().count())
                .isEqualTo(1L);
    }
}
