package com.king.ai.compliance.access;

import com.king.ai.common.BizException;
import com.king.ai.common.security.SpaceRole;

import java.util.Locale;
import java.util.Set;

public final class AccessReviewDecisions {

    public static final String CONFIRM = "CONFIRM";
    public static final String ACKNOWLEDGE = "ACKNOWLEDGE";
    public static final String DOWNGRADE = "DOWNGRADE";
    public static final String REMOVE = "REMOVE";

    private static final Set<String> ALL = Set.of(CONFIRM, ACKNOWLEDGE, DOWNGRADE, REMOVE);

    private AccessReviewDecisions() {
    }

    public static String parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALL.contains(normalized)) {
            throw BizException.badRequest("复核决策不合法");
        }
        return normalized;
    }

    public static void validate(String subjectType, String decision) {
        if ("MEMBER".equals(subjectType)) {
            return;
        }
        if (!ACKNOWLEDGE.equals(decision) && !CONFIRM.equals(decision)) {
            throw BizException.badRequest("供应商/MCP/公开智能体仅支持确认或知悉，请在对应模块执行变更");
        }
    }

    public static SpaceRole downgrade(SpaceRole current) {
        return switch (current) {
            case OWNER -> SpaceRole.ADMIN;
            case ADMIN -> SpaceRole.EDITOR;
            case EDITOR -> SpaceRole.VIEWER;
            case AUDITOR -> SpaceRole.VIEWER;
            case VIEWER -> throw BizException.badRequest("该成员已是最低角色，无法降级");
        };
    }
}
