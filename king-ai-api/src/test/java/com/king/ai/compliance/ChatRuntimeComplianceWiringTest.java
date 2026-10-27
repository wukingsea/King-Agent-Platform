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

    @Test
    void runtimeBoundsProviderCategoriesAtBothBlockSinks() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("private static String boundedCategory(String category)");
        // 输入/输出两个拦截分支的审计详情与指标标签共 4 处都必须经过白名单归一化。
        assertThat(source.split("boundedCategory\\(ex\\.category\\(\\)\\)", -1)).hasSize(5);
    }
}
