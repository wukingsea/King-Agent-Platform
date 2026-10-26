package com.king.ai.knowledge;

import com.king.ai.common.BizException;
import com.king.ai.common.Texts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-document knowledge ACL. An empty role list means "visible to the whole space";
 * otherwise a member can read the document when their role rank is at least the
 * configured threshold. AUDITOR is treated as read-only VIEWER-level access.
 */
public final class KnowledgeAcl {

    private static final Set<String> VALID_ROLES = Set.of("VIEWER", "EDITOR", "ADMIN", "OWNER", "AUDITOR");

    private KnowledgeAcl() {
    }

    public static List<String> parse(String value) {
        if (Texts.isBlank(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .toList();
    }

    public static String normalize(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            if (Texts.isBlank(role)) {
                continue;
            }
            String value = role.trim().toUpperCase(Locale.ROOT);
            if (!VALID_ROLES.contains(value)) {
                throw BizException.badRequest("无效的文档访问角色：" + role);
            }
            normalized.add(value);
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    public static boolean canRead(String allowedRolesCsv, String role) {
        List<String> allowed = parse(allowedRolesCsv);
        if (allowed.isEmpty()) {
            return true;
        }
        int userRank = roleRank(role);
        return allowed.stream().anyMatch(value -> userRank >= roleRank(value));
    }

    static int roleRank(String role) {
        if (role == null) {
            return 1;
        }
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "OWNER" -> 4;
            case "ADMIN" -> 3;
            case "EDITOR" -> 2;
            default -> 1;
        };
    }
}
