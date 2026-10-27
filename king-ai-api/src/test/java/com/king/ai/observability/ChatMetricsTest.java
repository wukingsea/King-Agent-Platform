package com.king.ai.observability;

import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatMetrics metrics = new ChatMetrics(registry, properties());

    @Test
    void recordsRequestsRejectionsAndTools() {
        metrics.request("admin");
        metrics.rejected("space_quota");
        metrics.toolCall("MCP", true);

        assertThat(registry.get("king.ai.chat.requests").tag("source", "admin")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.rejected").tag("reason", "space_quota")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.tool_calls")
                .tag("source", "MCP").tag("result", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void tracksActiveChatsGloballyAndBySpaceWithCap() {
        metrics.activeStarted(7L);
        metrics.activeStarted(7L);

        assertThat(registry.get("king.ai.chat.active").gauge().value()).isEqualTo(2);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "7")
                .gauge().value()).isEqualTo(2);

        metrics.activeFinished(7L);
        assertThat(registry.get("king.ai.chat.active").gauge().value()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "7")
                .gauge().value()).isEqualTo(1);
    }

    @Test
    void capsSpaceSeriesAndFoldsOverflowIntoOther() {
        for (long space = 1; space <= 199; space++) {
            metrics.activeStarted(space);
        }
        metrics.activeStarted(200L);
        metrics.activeStarted(201L);

        assertThat(registry.get("king.ai.chat.active.space").gauges()).hasSize(200);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "other")
                .gauge().value()).isEqualTo(2);
        assertThat(registry.find("king.ai.chat.active.space").tag("space", "200").gauge())
                .isNull();

        metrics.activeFinished(200L);
        metrics.activeFinished(201L);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "other")
                .gauge().value()).isZero();
        metrics.activeFinished(1L);
        assertThat(registry.get("king.ai.chat.active.space").tag("space", "1")
                .gauge().value()).isZero();
    }

    @Test
    void recordsTtftOncePerAttemptAndResetsOnRetry() {
        ChatMetrics.TokenTimer timer = metrics.startTokenTimer("gpt-4o", "admin");
        timer.firstToken();
        timer.firstToken();
        timer.reset();
        timer.firstToken();

        assertThat(registry.get("king.ai.chat.ttft").tag("model", "gpt-4o")
                .tag("source", "admin").timer().count()).isEqualTo(2);
    }

    @Test
    void recordsFinishedDurationAndTokens() {
        metrics.finished("admin", "completed", 1200, 10, 20, "gpt-4o");

        assertThat(registry.get("king.ai.chat.duration").tag("source", "admin")
                .tag("status", "completed").timer().count()).isEqualTo(1);
        assertThat(registry.get("king.ai.chat.tokens").tag("type", "prompt")
                .tag("model", "gpt-4o").counter().count()).isEqualTo(10);
        assertThat(registry.get("king.ai.chat.tokens").tag("type", "completion")
                .tag("model", "gpt-4o").counter().count()).isEqualTo(20);
    }

    @Test
    void enforcesSseCapacity() {
        assertThat(metrics.tryAcquireSse()).isTrue();
        assertThat(metrics.tryAcquireSse()).isTrue();
        assertThat(metrics.tryAcquireSse()).isFalse();
        assertThat(registry.get("king.ai.sse.connections").gauge().value()).isEqualTo(2);

        metrics.releaseSse();
        metrics.releaseSse();
        metrics.releaseSse();
        assertThat(registry.get("king.ai.sse.connections").gauge().value()).isZero();
        metrics.sseReconnect();
        assertThat(registry.get("king.ai.sse.reconnects").counter().count()).isEqualTo(1);
    }

    private KingAiProperties properties() {
        KingAiProperties properties = new KingAiProperties();
        properties.getChat().setSseCapacity(2);
        return properties;
    }
}
