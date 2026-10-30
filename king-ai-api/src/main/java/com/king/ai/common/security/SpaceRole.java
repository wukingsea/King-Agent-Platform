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

    /**
     * AUDITOR sits outside the VIEWER→OWNER hierarchy: it is never implied by a lower
     * role, and only ADMIN/OWNER may act as auditors. Other checks keep the hierarchy.
     */
    public boolean satisfies(SpaceRole required) {
        if (required == null || this == required) {
            return true;
        }
        if (required == AUDITOR) {
            return this == ADMIN || this == OWNER;
        }
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
