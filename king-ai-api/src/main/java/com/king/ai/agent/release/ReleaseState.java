package com.king.ai.agent.release;

public enum ReleaseState {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    CANARY,
    PUBLISHED,
    REJECTED,
    ARCHIVED;

    public static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        for (ReleaseState state : values()) {
            if (state.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
