package com.king.ai.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionServiceTest {

    private final RedactionService service = new RedactionService();

    private ComplianceSettings enabled(String... custom) {
        return new ComplianceSettings(true, ModerationMode.OFF, ModerationAction.BLOCK,
                ModerationAction.BLOCK, List.of(),
                java.util.Arrays.stream(custom).map(java.util.regex.Pattern::compile).toList(), 128);
    }

    @Test
    void redactsPhoneEmailIdCardAndBankCard() {
        RedactionResult result = service.redact(
                "联系 13812345678 / a.b@example.com / 11010519491231002X / 6222020200112233446",
                enabled());

        assertThat(result.text()).doesNotContain("13812345678");
        assertThat(result.text()).doesNotContain("a.b@example.com");
        assertThat(result.text()).doesNotContain("11010519491231002X");
        assertThat(result.text()).doesNotContain("6222020200112233446");
        assertThat(result.text()).contains("[REDACTED:PHONE]");
        assertThat(result.text()).contains("[REDACTED:EMAIL]");
        assertThat(result.text()).contains("[REDACTED:ID_CARD]");
        assertThat(result.text()).contains("[REDACTED:BANK_CARD]");
        assertThat(result.hits()).hasSize(4);
    }

    @Test
    void redactsApiKeysAndBearerTokens() {
        RedactionResult result = service.redact(
                "key=sk-live-abcdefghijklmnopqrstuvwx header=Bearer abcdefghijklmnopqrstuvwxyz0123456789",
                enabled());

        assertThat(result.text()).doesNotContain("sk-live-abcdefghijklmnopqrstuvwx");
        assertThat(result.text()).doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(result.text()).contains("[REDACTED:API_KEY]");
        assertThat(result.text()).contains("[REDACTED:BEARER]");
    }

    @Test
    void ignoresInvalidBankCardAndIdChecksums() {
        RedactionResult result = service.redact(
                "卡号 6222020200112233445 证件 110105194912310021", enabled());

        assertThat(result.text()).contains("6222020200112233445");
        assertThat(result.text()).contains("110105194912310021");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void appliesCustomPattern() {
        RedactionResult result = service.redact("员工编号 EMP-123456", enabled("EMP-\\d{6}"));

        assertThat(result.text()).isEqualTo("员工编号 [REDACTED:CUSTOM]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("custom-1"));
    }

    @Test
    void redactionIsIdempotent() {
        RedactionResult first = service.redact("13812345678", enabled());
        RedactionResult second = service.redact(first.text(), enabled());

        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.hits()).isEmpty();
    }

    @Test
    void disabledOrBlankReturnsOriginal() {
        ComplianceSettings disabled = new ComplianceSettings(false, ModerationMode.OFF,
                ModerationAction.BLOCK, ModerationAction.BLOCK, List.of(), List.of(), 128);

        assertThat(service.redact("13812345678", disabled).text()).isEqualTo("13812345678");
        assertThat(service.redact("", enabled()).text()).isEmpty();
        assertThat(service.redact(null, enabled()).text()).isEmpty();
    }
}
