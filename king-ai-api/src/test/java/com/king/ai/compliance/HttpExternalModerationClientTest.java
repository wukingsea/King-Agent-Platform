package com.king.ai.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.config.KingAiProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpExternalModerationClientTest {

    @Test
    void rejectsBlockedOutboundUrlBeforeSending() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getModeration().setExternalUrl("http://127.0.0.1:9000/check");
        OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
        when(guard.violation("http://127.0.0.1:9000/check")).thenReturn("不允许访问内网地址");
        HttpExternalModerationClient client = new HttpExternalModerationClient(
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(), properties, guard);

        assertThatThrownBy(() -> client.check("hello",
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ModerationUnavailableException.class)
                .hasMessageContaining("不允许访问内网地址");
    }
}
