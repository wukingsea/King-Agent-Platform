package com.king.ai.compliance;

import com.king.ai.common.BizException;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.compliance.access.AccessReviewDecisions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessReviewDecisionsTest {

    @Test
    void downgradeFollowsRoleLadder() {
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.OWNER)).isEqualTo(SpaceRole.ADMIN);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.ADMIN)).isEqualTo(SpaceRole.EDITOR);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.EDITOR)).isEqualTo(SpaceRole.VIEWER);
        assertThat(AccessReviewDecisions.downgrade(SpaceRole.AUDITOR)).isEqualTo(SpaceRole.VIEWER);
    }

    @Test
    void viewerCannotBeDowngraded() {
        assertThatThrownBy(() -> AccessReviewDecisions.downgrade(SpaceRole.VIEWER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最低角色");
    }

    @Test
    void nonMemberCanOnlyBeAcknowledged() {
        assertThatThrownBy(() -> AccessReviewDecisions.validate("SUPPLIER", "REMOVE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("供应商");
        AccessReviewDecisions.validate("SUPPLIER", "ACKNOWLEDGE");
        AccessReviewDecisions.validate("MCP", "CONFIRM");
        AccessReviewDecisions.validate("PUBLIC_AGENT", "ACKNOWLEDGE");
    }

    @Test
    void parseNormalizesCaseAndWhitespace() {
        assertThat(AccessReviewDecisions.parse(" remove ")).isEqualTo("REMOVE");
        assertThat(AccessReviewDecisions.parse("confirm")).isEqualTo("CONFIRM");
    }

    @Test
    void parseRejectsUnknownOrBlankDecision() {
        assertThatThrownBy(() -> AccessReviewDecisions.parse("MAYBE"))
                .isInstanceOf(BizException.class)
                .hasMessage("复核决策不合法");
        assertThatThrownBy(() -> AccessReviewDecisions.parse(null))
                .isInstanceOf(BizException.class)
                .hasMessage("复核决策不合法");
        assertThatThrownBy(() -> AccessReviewDecisions.parse("   "))
                .isInstanceOf(BizException.class)
                .hasMessage("复核决策不合法");
    }

    @Test
    void memberSupportsAllDecisions() {
        AccessReviewDecisions.validate("MEMBER", AccessReviewDecisions.REMOVE);
        AccessReviewDecisions.validate("MEMBER", AccessReviewDecisions.DOWNGRADE);
        AccessReviewDecisions.validate("MEMBER", AccessReviewDecisions.ACKNOWLEDGE);
        AccessReviewDecisions.validate("MEMBER", AccessReviewDecisions.CONFIRM);
    }

    @Test
    void nonMemberRejectsDowngrade() {
        assertThatThrownBy(() -> AccessReviewDecisions.validate("MCP", "DOWNGRADE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("供应商");
    }
}
