package com.king.ai.compliance;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record RedactionResult(String text, List<RedactionHit> hits) {

    public RedactionResult {
        text = text == null ? "" : text;
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public int redactedCount() {
        return hits.size();
    }

    public Set<String> ruleIds() {
        return hits.stream().map(RedactionHit::ruleId).collect(Collectors.toSet());
    }
}
