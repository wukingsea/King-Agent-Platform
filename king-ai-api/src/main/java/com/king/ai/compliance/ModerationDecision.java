package com.king.ai.compliance;

public record ModerationDecision(ModerationVerdict verdict, String category, String reason) {

    public static ModerationDecision allow() {
        return new ModerationDecision(ModerationVerdict.ALLOW, "NONE", "");
    }

    public boolean blocking(boolean publicChat) {
        return verdict == ModerationVerdict.BLOCK
                || (publicChat && verdict == ModerationVerdict.REVIEW);
    }
}
