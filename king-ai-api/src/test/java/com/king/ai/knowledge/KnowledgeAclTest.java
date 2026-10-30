package com.king.ai.knowledge;

import com.king.ai.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAclTest {

    @Test
    void blankAclMeansVisibleToEveryone() {
        assertThat(KnowledgeAcl.parse(null)).isEmpty();
        assertThat(KnowledgeAcl.canRead(null, "VIEWER")).isTrue();
        assertThat(KnowledgeAcl.canRead("", "AUDITOR")).isTrue();
    }

    @Test
    void roleListIsNormalizedAndDeduplicated() {
        assertThat(KnowledgeAcl.normalize(List.of(" editor ", "EDITOR", "admin")))
                .isEqualTo("EDITOR,ADMIN");
        assertThat(KnowledgeAcl.normalize(List.of())).isNull();
        assertThat(KnowledgeAcl.normalize(null)).isNull();
    }

    @Test
    void invalidRoleIsRejected() {
        assertThatThrownBy(() -> KnowledgeAcl.normalize(List.of("ROOT")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无效的文档访问角色");
    }

    @Test
    void editorThresholdAllowsEditorsAndAboveOnly() {
        assertThat(KnowledgeAcl.canRead("EDITOR", "EDITOR")).isTrue();
        assertThat(KnowledgeAcl.canRead("EDITOR", "ADMIN")).isTrue();
        assertThat(KnowledgeAcl.canRead("EDITOR", "OWNER")).isTrue();
        assertThat(KnowledgeAcl.canRead("EDITOR", "VIEWER")).isFalse();
        assertThat(KnowledgeAcl.canRead("EDITOR", "AUDITOR")).isFalse();
    }

    @Test
    void adminThresholdBlocksEditors() {
        assertThat(KnowledgeAcl.canRead("ADMIN", "EDITOR")).isFalse();
        assertThat(KnowledgeAcl.canRead("ADMIN", "ADMIN")).isTrue();
    }

    @Test
    void viewerThresholdIncludesAuditors() {
        assertThat(KnowledgeAcl.canRead("VIEWER", "AUDITOR")).isTrue();
        assertThat(KnowledgeAcl.canRead("VIEWER", "VIEWER")).isTrue();
    }

    @Test
    void multipleThresholdsUseTheMostPermissiveOne() {
        assertThat(KnowledgeAcl.canRead("EDITOR,OWNER", "EDITOR")).isTrue();
        assertThat(KnowledgeAcl.canRead("EDITOR,OWNER", "VIEWER")).isFalse();
    }

    @Test
    void unknownRoleFallsBackToViewerLevel() {
        assertThat(KnowledgeAcl.roleRank(null)).isEqualTo(1);
        assertThat(KnowledgeAcl.roleRank("AUDITOR")).isEqualTo(1);
        assertThat(KnowledgeAcl.roleRank("VIEWER")).isEqualTo(1);
    }

    @Test
    void readableThresholdsGrowWithRoleRank() {
        assertThat(KnowledgeAcl.readableRoles(null)).containsExactly("VIEWER", "AUDITOR");
        assertThat(KnowledgeAcl.readableRoles("VIEWER")).containsExactly("VIEWER", "AUDITOR");
        assertThat(KnowledgeAcl.readableRoles("AUDITOR")).containsExactly("VIEWER", "AUDITOR");
        assertThat(KnowledgeAcl.readableRoles("EDITOR"))
                .containsExactly("VIEWER", "AUDITOR", "EDITOR");
        assertThat(KnowledgeAcl.readableRoles("ADMIN"))
                .containsExactly("VIEWER", "AUDITOR", "EDITOR", "ADMIN");
        assertThat(KnowledgeAcl.readableRoles("OWNER"))
                .containsExactly("VIEWER", "AUDITOR", "EDITOR", "ADMIN", "OWNER");
    }
}
