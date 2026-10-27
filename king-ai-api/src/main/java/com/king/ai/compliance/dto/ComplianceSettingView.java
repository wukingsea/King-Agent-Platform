package com.king.ai.compliance.dto;

import java.util.List;

public record ComplianceSettingView(
        boolean piiEnabled,
        String moderationMode,
        String inputAction,
        String outputAction,
        List<String> blocklist,
        List<String> customPiiPatterns
) {
}
