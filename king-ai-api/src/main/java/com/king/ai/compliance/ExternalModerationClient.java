package com.king.ai.compliance;

public interface ExternalModerationClient {

    ModerationDecision check(String text, ModerationContext context);
}
