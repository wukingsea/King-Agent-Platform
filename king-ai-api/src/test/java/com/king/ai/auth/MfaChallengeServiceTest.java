package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaChallengeServiceTest {

    private KingAiProperties properties;
    private MfaChallengeService service;

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        service = new MfaChallengeService(properties);
    }

    @Test
    void signedChallengeRoundTripsUserId() {
        String challenge = service.issue(42L);

        assertThat(service.verify(challenge)).isEqualTo(42L);
    }

    @Test
    void tamperedChallengeIsRejected() {
        String challenge = service.issue(42L);
        String tampered = challenge.substring(0, challenge.length() - 2) + "aa";

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二次验证会话无效");
    }

    @Test
    void expiredChallengeIsRejected() {
        properties.getMfa().setChallengeTtl(Duration.ofSeconds(-1));
        String challenge = service.issue(42L);

        assertThatThrownBy(() -> service.verify(challenge))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    void challengeFromAnotherSecretIsRejected() {
        String challenge = service.issue(42L);
        KingAiProperties other = new KingAiProperties();
        other.setAppSecret("a-completely-different-secret");

        assertThatThrownBy(() -> new MfaChallengeService(other).verify(challenge))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二次验证会话无效");
    }
}
