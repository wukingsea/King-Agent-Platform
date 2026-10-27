package com.king.ai.compliance;

import com.king.ai.compliance.ChatInputCompliance.PreparedInput;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatInputComplianceTest {

    private final KingAiProperties properties = new KingAiProperties();
    private final ModerationService moderation = new ModerationService(
            new LocalRuleModerator(), properties, (text, context) -> ModerationDecision.allow());
    private final ChatInputCompliance input = new ChatInputCompliance(
            new RedactionService(), moderation, new LocalRuleModerator());

    private ComplianceSettings settings(ModerationMode mode, ModerationAction action) {
        return new ComplianceSettings(true, mode, action, action, List.of("违禁词"),
                List.of(), 128);
    }

    @Test
    void redactsPiiBeforeReturning() {
        PreparedInput prepared = input.prepare("电话13812345678",
                settings(ModerationMode.OFF, ModerationAction.BLOCK),
                new ModerationContext(1L, false, "input"));

        assertThat(prepared.text()).contains("[REDACTED:PHONE]");
        assertThat(prepared.text()).doesNotContain("13812345678");
        assertThat(prepared.redactedCount()).isEqualTo(1);
    }

    @Test
    void blocksConfiguredTermForPublicChat() {
        assertThatThrownBy(() -> input.prepare("违禁词",
                settings(ModerationMode.LOCAL, ModerationAction.BLOCK),
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ContentModerationException.class);
    }

    @Test
    void warnMasksConfiguredTermForInternalChat() {
        PreparedInput prepared = input.prepare("包含违禁词的内容",
                settings(ModerationMode.LOCAL, ModerationAction.WARN),
                new ModerationContext(1L, false, "input"));

        assertThat(prepared.text()).isEqualTo("包含[已屏蔽]的内容");
        assertThat(prepared.masked()).isTrue();
    }

    @Test
    void reviewBlocksPublicChatEvenWhenActionIsWarn() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.WARN, ModerationAction.WARN, List.of(),
                List.of(), 128);
        String spam = "https://a.com/1 https://a.com/2 https://a.com/3 "
                + "https://a.com/4 https://a.com/5 https://a.com/6";

        assertThatThrownBy(() -> input.prepare(spam, settings,
                new ModerationContext(1L, true, "input")))
                .isInstanceOf(ContentModerationException.class);
    }
}
