package com.king.ai.compliance;

import java.util.Locale;

public enum ModerationMode {
    OFF, LOCAL, EXTERNAL, HYBRID;

    public static ModerationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return ModerationMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean usesLocal() {
        return this == LOCAL || this == HYBRID;
    }

    public boolean usesExternal() {
        return this == EXTERNAL || this == HYBRID;
    }
}
