package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRuntimeComplianceWiringTest {

    private static final Path RUNTIME = Path.of(
            "src/main/java/com/king/ai/chat/ChatRuntime.java");

    @Test
    void runtimeChecksInputBeforeCreatingEmitter() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("chatInputCompliance.prepare(");
        assertThat(source.indexOf("chatInputCompliance.prepare("))
                .isLessThan(source.indexOf("SseEmitter emitter = new SseEmitter"));
    }

    @Test
    void runtimeGuardsStreamingContentAndReasoning() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("contentGuard.accept(delta)");
        assertThat(source).contains("reasoningGuard.accept(delta)");
        assertThat(source).contains("contentGuard.flush()");
        assertThat(source).contains("reasoningGuard.flush()");
    }

    @Test
    void runtimePersistsOnlyGuardedText() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("content.append(safe)");
        assertThat(source).doesNotContain("content.append(delta)");
        assertThat(source).contains("auditLogService.recordEvent(");
    }
}
