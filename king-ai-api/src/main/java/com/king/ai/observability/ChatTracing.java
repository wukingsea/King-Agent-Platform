package com.king.ai.observability;

import com.king.ai.common.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatTracing {

    private final Tracer tracer;

    public ChatTracing(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable(() -> Tracer.NOOP);
    }

    public Span start(String name) {
        Span span = tracer.nextSpan().name(name).start();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            span.tag("request.id", requestId);
        }
        return span;
    }

    public Span start(String name, Span parent) {
        Span span = tracer.nextSpan(parent).name(name).start();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            span.tag("request.id", requestId);
        }
        return span;
    }

    public Tracer.SpanInScope openScope(Span span) {
        return tracer.withSpan(span);
    }

    public void tag(Span span, String key, String value) {
        if (span != null && key != null && value != null) {
            span.tag(key, value);
        }
    }

    public void error(Span span, Throwable error) {
        if (span != null && error != null) {
            span.error(error);
        }
    }

    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    public String traceId() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop() || span.context() == null) {
            return null;
        }
        return span.context().traceId();
    }

    public String spanId() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop() || span.context() == null) {
            return null;
        }
        return span.context().spanId();
    }
}
