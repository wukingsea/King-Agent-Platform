package com.king.ai.compliance;

import java.util.Locale;

public enum ModerationAction {
    BLOCK, WARN;

    public static ModerationAction parse(String value) {
        if (value == null || value.isBlank()) {
            return BLOCK;
        }
        return ModerationAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
