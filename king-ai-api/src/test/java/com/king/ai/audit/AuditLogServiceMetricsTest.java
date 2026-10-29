package com.king.ai.audit;

import com.king.ai.audit.entity.AuditLog;
import com.king.ai.audit.mapper.AuditLogMapper;
import com.king.ai.config.KingAiProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceMetricsTest {

    @Test
    void incrementsFailureCounterWhenInsertFails() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));
        KingAiProperties properties = new KingAiProperties();
        properties.getAudit().setEnabled(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditLogService service = new AuditLogService(mapper, properties, registry);

        service.recordChat(1L, 2L, "tester", "chat.send", "9", "SUCCESS",
                "detail", "127.0.0.1", "test-agent", "req-1");

        assertThat(registry.get("king.ai.audit.write.failures").counter().count())
                .isEqualTo(1.0);
        verify(mapper).insert(any(AuditLog.class));
    }

    @Test
    void metricFailureDoesNotEscapeIntoTheBusinessPath() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));
        KingAiProperties properties = new KingAiProperties();
        properties.getAudit().setEnabled(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                if ("king.ai.audit.write.failures".equals(name)) {
                    throw new IllegalStateException("registry down");
                }
                return super.counter(name, tags);
            }
        };
        AuditLogService service = new AuditLogService(mapper, properties, registry);

        assertThatCode(() -> service.recordChat(1L, 2L, "tester", "chat.send", "9", "SUCCESS",
                "detail", "127.0.0.1", "test-agent", "req-1"))
                .doesNotThrowAnyException();
    }
}
