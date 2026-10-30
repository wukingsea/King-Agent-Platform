package com.king.ai.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceRoleAccessTest {

    @Test
    void auditorIsNotGrantedByHierarchy() {
        assertThat(SpaceRole.VIEWER.satisfies(SpaceRole.AUDITOR)).isFalse();
        assertThat(SpaceRole.EDITOR.satisfies(SpaceRole.AUDITOR)).isFalse();
        assertThat(SpaceRole.AUDITOR.satisfies(SpaceRole.AUDITOR)).isTrue();
        assertThat(SpaceRole.ADMIN.satisfies(SpaceRole.AUDITOR)).isTrue();
        assertThat(SpaceRole.OWNER.satisfies(SpaceRole.AUDITOR)).isTrue();
    }

    @Test
    void normalHierarchyStillApplies() {
        assertThat(SpaceRole.EDITOR.satisfies(SpaceRole.VIEWER)).isTrue();
        assertThat(SpaceRole.ADMIN.satisfies(SpaceRole.EDITOR)).isTrue();
        assertThat(SpaceRole.OWNER.satisfies(SpaceRole.ADMIN)).isTrue();
        assertThat(SpaceRole.VIEWER.satisfies(SpaceRole.EDITOR)).isFalse();
        assertThat(SpaceRole.EDITOR.satisfies(SpaceRole.ADMIN)).isFalse();
        assertThat(SpaceRole.ADMIN.satisfies(SpaceRole.OWNER)).isFalse();
    }

    @Test
    void auditorKeepsReadOnlyAccess() {
        assertThat(SpaceRole.AUDITOR.satisfies(SpaceRole.VIEWER)).isTrue();
        assertThat(SpaceRole.AUDITOR.satisfies(SpaceRole.EDITOR)).isFalse();
        assertThat(SpaceRole.AUDITOR.satisfies(SpaceRole.ADMIN)).isFalse();
    }

    @Test
    void viewerAndEditorCannotPassAuditEndpoints() {
        SpaceRole[] auditRoles = {SpaceRole.ADMIN, SpaceRole.AUDITOR};
        assertThat(principal("VIEWER").hasAnySpaceRole(auditRoles)).isFalse();
        assertThat(principal("EDITOR").hasAnySpaceRole(auditRoles)).isFalse();
        assertThat(principal("AUDITOR").hasAnySpaceRole(auditRoles)).isTrue();
        assertThat(principal("ADMIN").hasAnySpaceRole(auditRoles)).isTrue();
        assertThat(principal("OWNER").hasAnySpaceRole(auditRoles)).isTrue();
    }

    @Test
    void globalAdminBypassesSpaceRoleChecks() {
        UserPrincipal admin = new UserPrincipal(1L, "admin", "管理员", "ADMIN", 9L, "VIEWER", false);
        assertThat(admin.hasAnySpaceRole(new SpaceRole[]{SpaceRole.AUDITOR})).isTrue();
        assertThat(admin.canAuditSpace()).isTrue();
    }

    @Test
    void auditCapabilityMatchesRoleMatrix() {
        assertThat(principal("VIEWER").canAuditSpace()).isFalse();
        assertThat(principal("EDITOR").canAuditSpace()).isFalse();
        assertThat(principal("AUDITOR").canAuditSpace()).isTrue();
        assertThat(principal("ADMIN").canAuditSpace()).isTrue();
        assertThat(principal("OWNER").canAuditSpace()).isTrue();
    }

    private static UserPrincipal principal(String role) {
        return new UserPrincipal(2L, "user", "用户", "USER", 9L, role, false);
    }
}
