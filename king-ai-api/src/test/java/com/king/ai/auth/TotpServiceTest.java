package com.king.ai.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    /** RFC 6238 appendix B secret: ASCII "12345678901234567890". */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final TotpService service = new TotpService();

    @Test
    void generatesRfc6238Vectors() {
        assertThat(TotpService.generateCode(RFC_SECRET, 0)).isEqualTo("755224");
        assertThat(TotpService.generateCode(RFC_SECRET, 1)).isEqualTo("287082");
        assertThat(TotpService.generateCode(RFC_SECRET, 37037036)).isEqualTo("081804");
        assertThat(TotpService.generateCode(RFC_SECRET, 66666666)).isEqualTo("279037");
        assertThat(TotpService.generateCode(RFC_SECRET, 666666666)).isEqualTo("353130");
    }

    @Test
    void newSecretIsDecodableTwentyByteKey() {
        String secret = service.newSecret();

        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(TotpService.base32Decode(secret)).hasSize(20);
    }

    @Test
    void otpauthUrlCarriesSecretAndIssuer() {
        String url = service.otpauthUrl("King AI", "alice", RFC_SECRET);

        assertThat(url).startsWith("otpauth://totp/King%20AI:alice?")
                .contains("secret=" + RFC_SECRET)
                .contains("issuer=King%20AI")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    void rejectsMalformedCodes() {
        assertThat(service.verify(RFC_SECRET, "abc", 1)).isFalse();
        assertThat(service.verify(RFC_SECRET, "12345", 1)).isFalse();
        assertThat(service.verify("", "123456", 1)).isFalse();
    }
}
