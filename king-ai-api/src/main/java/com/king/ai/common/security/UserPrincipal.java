package com.king.ai.common.security;

public record UserPrincipal(Long id, String username, String nickname, String role, Long spaceId,
                            String spaceRole, boolean mustChangePassword) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public SpaceRole roleInSpace() {
        return SpaceRole.parse(spaceRole);
    }

    public boolean canReadSpace() {
        return isAdmin() || spaceId != null;
    }

    public boolean canEditSpace() {
        return isAdmin() || roleInSpace().atLeast(SpaceRole.EDITOR);
    }

    public boolean canManageSpace() {
        return isAdmin() || roleInSpace().atLeast(SpaceRole.ADMIN);
    }

    public boolean canOwnSpace() {
        return isAdmin() || roleInSpace() == SpaceRole.OWNER;
    }

    public boolean canAuditSpace() {
        return isAdmin() || roleInSpace() == SpaceRole.AUDITOR
                || roleInSpace().atLeast(SpaceRole.ADMIN);
    }

    public boolean hasSpaceRole(SpaceRole required) {
        return isAdmin() || (spaceId != null && roleInSpace().atLeast(required));
    }

    public boolean hasAnySpaceRole(SpaceRole[] required) {
        if (isAdmin()) {
            return true;
        }
        if (spaceId == null) {
            return false;
        }
        SpaceRole current = roleInSpace();
        for (SpaceRole role : required) {
            if (current == role || current.atLeast(role)) {
                return true;
            }
        }
        return false;
    }

    public String visitorId() {
        return "user:" + id;
    }
}
