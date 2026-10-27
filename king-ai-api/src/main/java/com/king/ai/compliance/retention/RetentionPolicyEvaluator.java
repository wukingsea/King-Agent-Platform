package com.king.ai.compliance.retention;

import java.time.LocalDateTime;

/**
 * Pure retention arithmetic shared by the retention job and its tests.
 */
public final class RetentionPolicyEvaluator {

    private RetentionPolicyEvaluator() {
    }

    public static LocalDateTime cutoff(LocalDateTime now, int days) {
        return now.minusDays(Math.max(1, days));
    }

    public static boolean deletable(LocalDateTime activity, LocalDateTime cutoff, boolean held) {
        return !held && activity != null && activity.isBefore(cutoff);
    }
}
