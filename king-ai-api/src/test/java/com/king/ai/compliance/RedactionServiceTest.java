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

    @Test
    void reportsRuleMetadataAndOffsetsForPhoneMatch() {
        RedactionResult result = service.redact("tel 13812345678 end", enabled());

        assertThat(result.text()).isEqualTo("tel [REDACTED:PHONE] end");
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.ruleId()).isEqualTo("cn-mobile");
            assertThat(hit.category()).isEqualTo("PHONE");
            assertThat(hit.start()).isEqualTo(4);
            assertThat(hit.end()).isEqualTo(15);
        });
    }

    @Test
    void keepsEarlierLongerMatchWhenCustomPatternsOverlap() {
        RedactionResult result = service.redact("EMP-123456",
                enabled("EMP-\\d{6}", "\\d{6}"));

        assertThat(result.text()).isEqualTo("[REDACTED:CUSTOM]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("custom-1"));
    }

    @Test
    void keepsLongestCustomMatchWhenPatternsShareStart() {
        RedactionResult result = service.redact("EMP-123456-99",
                enabled("EMP-\\d{6}", "EMP-\\d{6}-\\d{2}"));

        assertThat(result.text()).isEqualTo("[REDACTED:CUSTOM]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("custom-2"));
    }

    @Test
    void redactsValidIpv4AndKeepsInvalidIpv4() {
        RedactionResult result = service.redact("ip 1.2.3.4 bad 999.1.1.1", enabled());

        assertThat(result.text()).isEqualTo("ip [REDACTED:IP] bad 999.1.1.1");
        assertThat(result.text()).doesNotContain("1.2.3.4");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.category()).isEqualTo("IP"));
    }

    @Test
    void zeroWidthCustomPatternDoesNotDuplicateTokens() {
        RedactionResult result = service.redact("abc", enabled(".*"));

        assertThat(result.text()).isEqualTo("[REDACTED:CUSTOM]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("custom-1"));
    }

    @Test
    void oversizeInputFailsClosed() {
        String text = "x".repeat(199_990) + "13812345678";
        assertThat(text).hasSize(200_001);

        RedactionResult result = service.redact(text, enabled());

        assertThat(result.text()).isEqualTo("[REDACTED:OVERSIZE]");
        assertThat(result.text()).doesNotContain("13812345678");
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.ruleId()).isEqualTo("oversize");
            assertThat(hit.category()).isEqualTo("OVERSIZE");
            assertThat(hit.start()).isZero();
            assertThat(hit.end()).isEqualTo(text.length());
        });
    }

    @Test
    void nullSettingsTreatsPiiAsEnabled() {
        RedactionResult result = service.redact("13812345678", null);

        assertThat(result.text()).isEqualTo("[REDACTED:PHONE]");
    }

    @Test
    void pathologicalCustomPatternFailsClosed() {
        String text = "a".repeat(64) + "!";

        RedactionResult result = service.redact(text, enabled("(a+)+$"));

        assertThat(result.text()).isEqualTo("[REDACTED:REGEX_LIMIT]");
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.ruleId()).isEqualTo("regex-limit");
            assertThat(hit.category()).isEqualTo("REGEX_LIMIT");
            assertThat(hit.start()).isZero();
            assertThat(hit.end()).isEqualTo(text.length());
        });
    }

    @Test
    void runtimeRegexBudgetFailsClosedOnCatastrophicBacktracking() {
        // This pattern is NOT rejected by hasUnboundedNestedQuantifier: (a|aa) contains no
        // unbounded quantifier and {1,64} is bounded. Only the 5M char-access budget stops the
        // exponential split of the 64 a's into 1- and 2-char repetitions.
        //
        // The similarly-shaped (a|aa)+$ cannot be used here: on this project's JDK 17.0.6 the
        // engine resolves that unbounded '+' loop in linear time (~393 charAt calls for 64 a's),
        // so it never reaches the budget and would return the raw text instead of the sentinel.
        String text = "a".repeat(64) + "!";

        RedactionResult result = service.redact(text, enabled("(a|aa){1,64}$"));

        assertThat(result.text()).isEqualTo("[REDACTED:REGEX_LIMIT]");
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.ruleId()).isEqualTo("regex-limit"));
    }
}
