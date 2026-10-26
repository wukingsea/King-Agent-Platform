package com.king.ai.common.security;

import java.util.Locale;

public enum SpaceRole {
    VIEWER(1),
    AUDITOR(1),
    EDITOR(2),
    ADMIN(3),
    OWNER(4);

    private final int level;

    SpaceRole(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean atLeast(SpaceRole required) {
        return level >= required.level;
    }

    public static SpaceRole parse(String value) {
        if (value == null || value.isBlank()) {
            return VIEWER;
        }
        try {
            return SpaceRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return VIEWER;
        }
    }
}
