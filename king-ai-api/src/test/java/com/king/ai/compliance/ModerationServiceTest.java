package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationServiceTest {

    private KingAiProperties properties;
    private ModerationService service;
    private final ModerationContext internal = new ModerationContext(1L, false, "input");
    private final ModerationContext publicChat = new ModerationContext(1L, true, "input");

    private ComplianceSettings settings(ModerationMode mode) {
        return new ComplianceSettings(true, mode, ModerationAction.BLOCK,
                ModerationAction.BLOCK, List.of("违禁词"), List.of(), 128);
    }

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        service = new ModerationService(new LocalRuleModerator(), properties,
                (text, context) -> {
                    throw new IllegalStateException("timeout");
                });
    }

    @Test
    void offModeBypassesAllChecks() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.OFF), publicChat);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.ALLOW);
    }

    @Test
    void localModeBlocksConfiguredTerm() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.LOCAL), internal);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void externalFailureFallsBackToLocalBlock() {
        ModerationScan scan = service.scan("违禁词", settings(ModerationMode.EXTERNAL), internal);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
    }

    @Test
    void externalFailureForPublicChatFailClosedWhenLocalAllows() {
        ModerationScan scan = service.scan("普通内容", settings(ModerationMode.EXTERNAL), publicChat);

        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(scan.decision().category()).isEqualTo("MODERATION_UNAVAILABLE");
    }

    @Test
    void externalDecisionIsUsedWhenAvailable() {
        AtomicBoolean called = new AtomicBoolean();
        ModerationService withExternal = new ModerationService(new LocalRuleModerator(), properties,
                (text, context) -> {
                    called.set(true);
                    return new ModerationDecision(ModerationVerdict.BLOCK, "EXTERNAL", "blocked");
                });

        ModerationScan scan = withExternal.scan("普通内容", settings(ModerationMode.EXTERNAL), internal);

        assertThat(called).isTrue();
        assertThat(scan.decision().verdict()).isEqualTo(ModerationVerdict.BLOCK);
        assertThat(scan.decision().category()).isEqualTo("EXTERNAL");
    }
}
