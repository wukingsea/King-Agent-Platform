package com.king.ai.compliance;

import java.util.List;
import java.util.regex.Pattern;

public record ComplianceSettings(
        boolean piiEnabled,
        ModerationMode moderationMode,
        ModerationAction inputAction,
        ModerationAction outputAction,
        List<String> blocklist,
        List<Pattern> customPiiPatterns,
        int streamHoldbackChars
) {
    public ComplianceSettings {
        blocklist = blocklist == null ? List.of() : List.copyOf(blocklist);
        customPiiPatterns = customPiiPatterns == null ? List.of() : List.copyOf(customPiiPatterns);
    }

    public boolean moderationEnabled() {
        return moderationMode != ModerationMode.OFF;
    }
}
