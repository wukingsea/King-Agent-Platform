package com.king.ai.compliance.access;

import com.king.ai.common.Texts;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AccessReviewReportBuilder {

    public record MemberSnapshot(Long userId, String username, String nickname, String role,
                                 LocalDateTime lastLoginAt) {
    }

    public record CredentialSnapshot(Long id, String name, LocalDateTime lastActivityAt) {
    }

    public record PublicAgentSnapshot(Long id, String name, String publicId,
                                      LocalDateTime lastActivityAt) {
    }

    public record AccessFinding(String subjectType, Long subjectId, String name, String role,
                                LocalDateTime lastActivityAt, List<String> flags) {
    }

    private AccessReviewReportBuilder() {
    }

    public static List<AccessFinding> build(List<MemberSnapshot> members,
                                            List<CredentialSnapshot> suppliers,
                                            List<CredentialSnapshot> mcps,
                                            List<PublicAgentSnapshot> publicAgents,
                                            LocalDateTime now, int staleDays) {
        int window = Math.max(1, staleDays);
        LocalDateTime stale = now.minusDays(window);
        List<AccessFinding> findings = new ArrayList<>();
        for (MemberSnapshot member : members) {
            Set<String> flags = new LinkedHashSet<>();
            if (member.lastLoginAt() == null) {
                flags.add("NEVER_LOGGED_IN");
            } else if (member.lastLoginAt().isBefore(stale)) {
                flags.add("UNUSED_" + window + "D");
            }
            if ("OWNER".equals(member.role())) {
                flags.add("OWNER");
            }
            String username = Texts.blankToDefault(member.username(), "-");
            String display = Texts.blankToDefault(member.nickname(), username);
            findings.add(new AccessFinding("MEMBER", member.userId(),
                    display + " (" + username + ")", member.role(),
                    member.lastLoginAt(), List.copyOf(flags)));
        }
        for (CredentialSnapshot supplier : suppliers) {
            findings.add(credential("SUPPLIER", supplier, stale, "供应商密钥"));
        }
        for (CredentialSnapshot mcp : mcps) {
            findings.add(credential("MCP", mcp, stale, "MCP 配置"));
        }
        for (PublicAgentSnapshot agent : publicAgents) {
            findings.add(new AccessFinding("PUBLIC_AGENT", agent.id(), agent.name(),
                    agent.publicId(), agent.lastActivityAt(), List.of("PUBLIC_EXPOSURE")));
        }
        return findings;
    }

    private static AccessFinding credential(String type, CredentialSnapshot snapshot,
                                            LocalDateTime stale, String label) {
        List<String> flags = snapshot.lastActivityAt() == null
                || snapshot.lastActivityAt().isBefore(stale)
                ? List.of("STALE_CREDENTIAL") : List.of();
        return new AccessFinding(type, snapshot.id(), snapshot.name(), label,
                snapshot.lastActivityAt(), flags);
    }
}
