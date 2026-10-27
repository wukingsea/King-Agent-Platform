package com.king.ai.common.web;

import com.king.ai.config.KingAiProperties;
import com.king.ai.observability.ChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SseReconnectFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatMetrics metrics = new ChatMetrics(registry, new KingAiProperties());
    private final SseReconnectFilter filter = new SseReconnectFilter(metrics);

    @Test
    void countsLastEventIdOnChatSend() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/send");
        request.addHeader("Last-Event-ID", "42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(registry.get("king.ai.sse.reconnects").counter().count()).isEqualTo(1);
    }

    @Test
    void ignoresRequestsWithoutLastEventIdAndOtherPaths() throws Exception {
        MockHttpServletRequest plain = new MockHttpServletRequest("POST", "/api/chat/send");
        filter.doFilter(plain, new MockHttpServletResponse(), (req, res) -> {
        });

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/agents");
        other.addHeader("Last-Event-ID", "42");
        filter.doFilter(other, new MockHttpServletResponse(), (req, res) -> {
        });

        // king.ai.sse.reconnects 为懒注册计数器：未发生重连时 meter 尚未创建，等价于计数为 0。
        assertThat(registry.find("king.ai.sse.reconnects").counter()).isNull();
    }
}
