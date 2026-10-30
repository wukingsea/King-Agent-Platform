package com.king.ai.sso;

import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcStateSignerTest {

    private final OidcStateSigner signer = new OidcStateSigner(new KingAiProperties());

    @Test
    void signedStateReturnsNonce() {
        String state = signer.issue("nonce-1", Duration.ofMinutes(5));

        assertThat(signer.verify(state)).isEqualTo("nonce-1");
    }

    @Test
    void tamperedStateIsRejected() {
        String state = signer.issue("nonce-1", Duration.ofMinutes(5));
        String tampered = state.substring(0, state.length() - 2) + "aa";

        assertThatThrownBy(() -> signer.verify(tampered))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("state 无效");
    }

    @Test
    void expiredStateIsRejected() {
        String state = signer.issue("nonce-1", Duration.ofSeconds(-1));

        assertThatThrownBy(() -> signer.verify(state))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已超时");
    }

    @Test
    void stateFromAnotherSecretIsRejected() {
        String state = signer.issue("nonce-1", Duration.ofMinutes(5));
        KingAiProperties other = new KingAiProperties();
        other.setAppSecret("different-platform-secret");

        assertThatThrownBy(() -> new OidcStateSigner(other).verify(state))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("state 无效");
    }
}
