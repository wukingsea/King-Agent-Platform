package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceStreamGuardTest {

    private static final ComplianceSettings PII = new ComplianceSettings(true,
            ModerationMode.OFF, ModerationAction.BLOCK, ModerationAction.BLOCK,
            List.of(), List.of(), 32);

    private final KingAiProperties properties = new KingAiProperties();
    private final RedactionService redaction = new RedactionService();
    private final LocalRuleModerator local = new LocalRuleModerator();
    private final ModerationService moderation = new ModerationService(local, properties,
            (text, context) -> ModerationDecision.allow());

    @Test
    void doesNotLeakPhoneSplitAcrossDeltas() {
        ComplianceStreamGuard guard = guard(PII, false);
        String first = guard.accept("我的手机号是1381234");
        String second = guard.accept("5678，请查收");
        String tail = guard.flush();

        assertThat(first + second + tail).doesNotContain("13812345678");
        assertThat(first + second + tail).contains("[REDACTED:PHONE]");
        assertThat(guard.countsByCategory()).containsEntry("PHONE", 1);
    }

    @Test
    void blocksTermSplitAcrossDeltasWhenOutputActionBlocks() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of("违禁词"),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, true);

        assertThatThrownBy(() -> {
            guard.accept("这里有违");
            guard.accept("禁词出现");
        }).isInstanceOf(ContentModerationException.class)
                .extracting("category").isEqualTo("BLOCKLIST");
    }

    @Test
    void blocksReviewSpamForPublicChatEvenWhenOutputActionWarns() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.WARN, ModerationAction.WARN, List.of(),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, true);
        String spam = "https://a.com/1 https://a.com/2 https://a.com/3 "
                + "https://a.com/4 https://a.com/5 https://a.com/6";

        assertThatThrownBy(() -> guard.accept(spam))
                .isInstanceOf(ContentModerationException.class)
                .extracting("category").isEqualTo("SPAM");
    }

    @Test
    void warnMasksTermAndKeepsStreaming() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.LOCAL,
                ModerationAction.BLOCK, ModerationAction.WARN, List.of("违禁词"),
                List.of(), 32);
        ComplianceStreamGuard guard = guard(settings, false);

        String text = guard.accept("这里有违禁词出现") + guard.flush();

        assertThat(text).doesNotContain("违禁词");
        assertThat(text).contains("[已屏蔽]");
    }

    @Test
    void resetDiscardsBufferedText() {
        ComplianceStreamGuard guard = guard(PII, false);
        guard.accept("1381234");
        guard.reset();

        assertThat(guard.flush()).isEmpty();
        assertThat(guard.countsByCategory()).isEmpty();
    }

    @Test
    void customPatternLongerThanWindowIsStillRedactedBeforeFlush() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.OFF,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of(),
                List.of(Pattern.compile("SECRET-[A-Z0-9]{40}")), 32);
        ComplianceStreamGuard guard = guard(settings, false);

        String text = guard.accept("token SECRET-") + guard.accept("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCD")
                + guard.flush();

        assertThat(text).doesNotContain("SECRET-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCD");
        assertThat(text).contains("[REDACTED:CUSTOM]");
    }

    @Test
    void doesNotEmitPartialPhoneAtWindowBoundary() {
        ComplianceStreamGuard guard = guard(PII, false);

        String first = guard.accept("x".repeat(40) + "13812345678" + "y".repeat(25));
        String tail = guard.flush();

        assertThat(first).isEqualTo("x".repeat(40));
        assertThat(first + tail).contains("[REDACTED:PHONE]");
        assertThat(first + tail).doesNotContain("13812345678");
        assertThat(guard.countsByCategory()).containsEntry("PHONE", 1);
    }

    @Test
    void failClosedSentinelDoesNotStallTheBuffer() {
        ComplianceSettings settings = new ComplianceSettings(true, ModerationMode.OFF,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of(),
                List.of(Pattern.compile("(a+)+b")), 32);
        ComplianceStreamGuard guard = guard(settings, false);

        String emitted = guard.accept("a".repeat(64));

        assertThat(emitted).isEqualTo("[REDACTED:REGEX_LIMIT]");
        assertThat(guard.flush()).isEmpty();
    }

    private ComplianceStreamGuard guard(ComplianceSettings settings, boolean block) {
        return new ComplianceStreamGuard(redaction, moderation, settings,
                new ModerationContext(1L, block, "output"), block);
    }
}
