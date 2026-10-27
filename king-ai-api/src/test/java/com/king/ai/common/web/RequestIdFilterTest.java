package com.king.ai.common.web;

import com.king.ai.observability.ChatTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void writesTraceAndSpanIdsToMdcAndResponseHeader() throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        RequestIdFilter filter = new RequestIdFilter(
                new ChatTracing(factory.getBeanProvider(Tracer.class)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/send");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Span span = tracer.nextSpan().name("http.server.requests").start();

        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY))
                        .isEqualTo(span.context().traceId());
                assertThat(MDC.get(RequestIdFilter.SPAN_ID_MDC_KEY))
                        .isEqualTo(span.context().spanId());
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank();
            });
        } finally {
            span.end();
        }

        assertThat(response.getHeader(RequestIdFilter.TRACE_HEADER))
                .isEqualTo(span.context().traceId());
        assertThat(MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void keepsRequestIdValidationAndGeneratesUuidWhenInvalid() throws Exception {
        SimpleTracer tracer = new SimpleTracer();
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("tracer", tracer);
        RequestIdFilter filter = new RequestIdFilter(
                new ChatTracing(factory.getBeanProvider(Tracer.class)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        request.addHeader(RequestIdFilter.HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("bad id with spaces");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }
}
