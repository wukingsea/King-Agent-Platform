package com.king.ai.chat;

import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceStreamGuard;
import com.king.ai.compliance.LocalRuleModerator;
import com.king.ai.compliance.ModerationAction;
import com.king.ai.compliance.ModerationContext;
import com.king.ai.compliance.ModerationMode;
import com.king.ai.compliance.ModerationService;
import com.king.ai.compliance.RedactionService;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamCheckpointTest {

    private static final ComplianceSettings SETTINGS = new ComplianceSettings(
            true, ModerationMode.OFF, ModerationAction.BLOCK, ModerationAction.BLOCK,
            List.of(), List.of(), 32);

    private final RedactionService redactionService = new RedactionService();
    private final ModerationService moderationService =
            new ModerationService(new LocalRuleModerator(), new KingAiProperties(), null);

    @Test
    void restoreRollsBackOnlyOutputProducedAfterCapture() {
        StringBuilder content = new StringBuilder("上一轮已输出：");
        StringBuilder reasoning = new StringBuilder("上一轮思考：");
        ComplianceStreamGuard contentGuard = guard("output");
        ComplianceStreamGuard reasoningGuard = guard("reasoning");

        content.append(contentGuard.accept("a".repeat(200)));
        reasoning.append(reasoningGuard.accept("r".repeat(200)));
        String contentAtCapture = content.toString();
        String reasoningAtCapture = reasoning.toString();
        ComplianceStreamGuard.Checkpoint contentGuardAtCapture = contentGuard.checkpoint();
        ComplianceStreamGuard.Checkpoint reasoningGuardAtCapture = reasoningGuard.checkpoint();
        ChatStreamCheckpoint checkpoint =
                ChatStreamCheckpoint.capture(content, reasoning, contentGuard, reasoningGuard);

        content.append("主模型半截输出");
        reasoning.append("主模型半截思考");
        content.append(contentGuard.accept("b".repeat(120)));
        reasoning.append(reasoningGuard.accept("s".repeat(120)));

        checkpoint.restore(content, reasoning, contentGuard, reasoningGuard);

        assertThat(content).hasToString(contentAtCapture);
        assertThat(reasoning).hasToString(reasoningAtCapture);
        assertThat(contentGuard.checkpoint()).isEqualTo(contentGuardAtCapture);
        assertThat(reasoningGuard.checkpoint()).isEqualTo(reasoningGuardAtCapture);
    }

    @Test
    void restoreClearsGuardCountsAccumulatedAfterCapture() {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ComplianceStreamGuard contentGuard = guard("output");
        ComplianceStreamGuard reasoningGuard = guard("reasoning");

        contentGuard.accept("contact a@b.com " + "x".repeat(64));
        ChatStreamCheckpoint checkpoint =
                ChatStreamCheckpoint.capture(content, reasoning, contentGuard, reasoningGuard);

        contentGuard.accept("second a@b.com " + "y".repeat(64));
        checkpoint.restore(content, reasoning, contentGuard, reasoningGuard);

        assertThat(contentGuard.countsByCategory()).isEqualTo(checkpoint.contentCheckpoint().counts());
    }

    private ComplianceStreamGuard guard(String surface) {
        return new ComplianceStreamGuard(redactionService, moderationService, SETTINGS,
                new ModerationContext(1L, false, surface), false);
    }
}
