package com.king.ai.compliance;

import java.util.List;

public record ModerationScan(ModerationDecision decision, List<ModerationHit> hits) {

    public ModerationScan {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static ModerationScan allow() {
        return new ModerationScan(ModerationDecision.allow(), List.of());
    }
}
