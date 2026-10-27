package com.king.ai.compliance;

import java.util.function.Predicate;
import java.util.regex.Pattern;

public record RedactionRule(String id, String category, Pattern pattern,
                            Predicate<String> validator, String replacement) {

    public boolean accepts(String value) {
        return validator == null || validator.test(value);
    }
}
