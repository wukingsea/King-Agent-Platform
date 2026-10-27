package com.king.ai.observability;

import com.king.ai.common.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTracingTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createsChildSpanInSameTraceAndTagsRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-1");
        SimpleTracer tracer = new SimpleTracer();
        ChatTracing tracing = tracing(tracer);

        Span parent = tracing.start("chat.request");
        try (Tracer.SpanInScope scope = tracing.openScope(parent)) {
            Span child = tracing.start("llm.chat");
            tracing.tag(child, "gen_ai.request.model", "gpt-4o");
            tracing.end(child);
            assertThat(tracing.traceId()).isEqualTo(parent.context().traceId());
        }
        tracing.end(parent);

        assertThat(tracer.getSpans()).extracting(SimpleSpan::getName)
                .containsExactlyInAnyOrder("chat.request", "llm.chat");
        SimpleSpan child = tracer.getSpans().stream()
                .filter(span -> "llm.chat".equals(span.getName()))
                .findFirst().orElseThrow();
        assertThat(child.getTraceId()).isEqualTo(parent.context().traceId());
        assertThat(child.getTags())
                .containsEntry("gen_ai.request.model", "gpt-4o")
                .containsEntry("request.id", "req-1");
    }

    @Test
    void recordsErrorOnSpan() {
        SimpleTracer tracer = new SimpleTracer();
        ChatTracing tracing = tracing(tracer);
        Span span = tracing.start("chat.persist");

        tracing.error(span, new IllegalStateException("db down"));
        tracing.end(span);

        assertThat(tracer.onlySpan().getError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fallsBackToNoopWhenTracingIsDisabled() {
        ChatTracing tracing = new ChatTracing(new StaticListableBeanFactory().getBeanProvider(Tracer.class));

        Span span = tracing.start("chat.request");
        tracing.tag(span, "k", "v");
        tracing.end(span);

        assertThat(tracing.traceId()).isNull();
        assertThat(tracing.spanId()).isNull();
    }

    private ChatTracing tracing(SimpleTracer tracer) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        return new ChatTracing(factory.getBeanProvider(Tracer.class));
    }
}
