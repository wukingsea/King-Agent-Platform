package com.king.ai.compliance;

import com.king.ai.compliance.retention.RetentionPolicyEvaluator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPolicyEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void cutoffUsesAtLeastOneDay() {
        assertThat(RetentionPolicyEvaluator.cutoff(NOW, 30))
                .isEqualTo(NOW.minusDays(30));
        assertThat(RetentionPolicyEvaluator.cutoff(NOW, 0))
                .isEqualTo(NOW.minusDays(1));
    }

    @Test
    void deletableRequiresExpiredAndNotHeld() {
        LocalDateTime cutoff = NOW.minusDays(30);

        assertThat(RetentionPolicyEvaluator.deletable(cutoff.minusSeconds(1), cutoff, false)).isTrue();
        assertThat(RetentionPolicyEvaluator.deletable(cutoff, cutoff, false)).isFalse();
        assertThat(RetentionPolicyEvaluator.deletable(cutoff.minusDays(1), cutoff, true)).isFalse();
        assertThat(RetentionPolicyEvaluator.deletable(null, cutoff, false)).isFalse();
    }
}
