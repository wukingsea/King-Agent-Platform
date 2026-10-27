package com.king.ai.compliance;

import com.king.ai.compliance.access.AccessReviewReportBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessReviewReportBuilderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void flagsNeverLoggedInAndUnusedMembers() {
        var findings = AccessReviewReportBuilder.build(
                List.of(new AccessReviewReportBuilder.MemberSnapshot(1L, "alice", "Alice",
                                "ADMIN", null),
                        new AccessReviewReportBuilder.MemberSnapshot(2L, "bob", "Bob",
                                "VIEWER", NOW.minusDays(120)),
                        new AccessReviewReportBuilder.MemberSnapshot(3L, "carol", "Carol",
                                "OWNER", NOW.minusDays(2))),
                List.of(), List.of(), List.of(), NOW, 90);

        assertThat(findings).filteredOn(item -> item.subjectId().equals(1L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("NEVER_LOGGED_IN"));
        assertThat(findings).filteredOn(item -> item.subjectId().equals(2L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("UNUSED_90D"));
        assertThat(findings).filteredOn(item -> item.subjectId().equals(3L))
                .singleElement().satisfies(item -> assertThat(item.flags())
                        .contains("OWNER").doesNotContain("UNUSED_90D"));
    }

    @Test
    void flagsStaleCredentialsAndPublicAgents() {
        var findings = AccessReviewReportBuilder.build(
                List.of(),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(5L, "openai",
                        NOW.minusDays(100))),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(6L, "mcp-a", null)),
                List.of(new AccessReviewReportBuilder.PublicAgentSnapshot(7L, "客服", "pub_1",
                        NOW.minusDays(1))),
                NOW, 90);

        assertThat(findings).extracting(item -> item.subjectType())
                .containsExactly("SUPPLIER", "MCP", "PUBLIC_AGENT");
        assertThat(findings.get(0).flags()).contains("STALE_CREDENTIAL");
        assertThat(findings.get(1).flags()).contains("STALE_CREDENTIAL");
        assertThat(findings.get(2).flags()).contains("PUBLIC_EXPOSURE");
    }

    @Test
    void emptySnapshotsProduceNoFindings() {
        assertThat(AccessReviewReportBuilder.build(List.of(), List.of(), List.of(), List.of(),
                NOW, 90)).isEmpty();
    }

    @Test
    void staleWindowFloorsAtOneDay() {
        var findings = AccessReviewReportBuilder.build(
                List.of(new AccessReviewReportBuilder.MemberSnapshot(1L, "alice", "Alice",
                                "VIEWER", NOW.minusHours(2)),
                        new AccessReviewReportBuilder.MemberSnapshot(2L, "bob", "Bob",
                                "VIEWER", NOW.minusDays(2))),
                List.of(), List.of(), List.of(), NOW, 0);

        assertThat(findings.get(0).flags()).doesNotContain("UNUSED_1D");
        assertThat(findings.get(1).flags()).contains("UNUSED_1D");
    }

    @Test
    void memberNameKeepsUsernameForReadability() {
        var findings = AccessReviewReportBuilder.build(
                List.of(new AccessReviewReportBuilder.MemberSnapshot(1L, "alice", "Alice",
                        "VIEWER", NOW)),
                List.of(), List.of(), List.of(), NOW, 90);

        assertThat(findings.get(0).name()).isEqualTo("Alice (alice)");
        assertThat(findings.get(0).subjectType()).isEqualTo("MEMBER");
        assertThat(findings.get(0).role()).isEqualTo("VIEWER");
    }

    @Test
    void blankNicknameFallsBackToUsernameInsteadOfNullText() {
        var findings = AccessReviewReportBuilder.build(
                List.of(new AccessReviewReportBuilder.MemberSnapshot(1L, "alice", null,
                                "VIEWER", NOW),
                        new AccessReviewReportBuilder.MemberSnapshot(2L, "bob", "   ",
                                "VIEWER", NOW)),
                List.of(), List.of(), List.of(), NOW, 90);

        assertThat(findings.get(0).name()).isEqualTo("alice (alice)");
        assertThat(findings.get(1).name()).isEqualTo("bob (bob)");
        assertThat(findings).extracting(AccessReviewReportBuilder.AccessFinding::name)
                .allSatisfy(name -> assertThat(name).doesNotContain("null"));
    }
}
