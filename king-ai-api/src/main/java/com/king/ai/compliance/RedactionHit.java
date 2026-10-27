package com.king.ai.compliance;

public record RedactionHit(String ruleId, String category, int start, int end) {
}
