package com.king.ai.chat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRuntimeFallbackWiringTest {

    private static final Path RUNTIME = Path.of(
            "src/main/java/com/king/ai/chat/ChatRuntime.java");

    @Test
    void fallbackRestoresRoundCheckpointBeforeReplayingWithFallbackModel() throws Exception {
        String source = Files.readString(RUNTIME);

        int fallbackReplay = source.indexOf("streamWithRetry(fallback");
        int restore = source.lastIndexOf(
                "checkpoint.restore(content, reasoning, contentGuard, reasoningGuard);", fallbackReplay);

        assertThat(fallbackReplay).isGreaterThan(0);
        assertThat(restore).isGreaterThan(0);
        assertThat(restore).isLessThan(fallbackReplay);
    }

    @Test
    void degradedEventCarriesResetBoundaryForTheClient() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("\"reason\", \"model_fallback\"");
        assertThat(source).contains("\"reset\", true");
        assertThat(source).contains("\"contentLength\", content.length()");
        assertThat(source).contains("\"reasoningLength\", reasoning.length()");
    }

    @Test
    void persistedModelIsTheOneThatActuallyServedTheRound() throws Exception {
        String source = Files.readString(RUNTIME);

        assertThat(source).contains("usedModel = streamOutcome.model();");
        assertThat(source).contains("usedModel, promptTokens, completionTokens, duration");
    }
}
