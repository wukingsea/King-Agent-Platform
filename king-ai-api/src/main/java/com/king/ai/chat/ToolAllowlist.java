package com.king.ai.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.Texts;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent tool allowlist parsing and matching. An empty configuration means "no restriction",
 * while a malformed configuration must fail closed instead of silently granting every tool.
 */
@Slf4j
public final class ToolAllowlist {

    public static final String DENY_ALL = "\u0000invalid-allowlist";

    private ToolAllowlist() {
    }

    public static List<String> parse(String json, ObjectMapper objectMapper) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> patterns = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            if (patterns == null) {
                return List.of(DENY_ALL);
            }
            return patterns.stream()
                    .filter(pattern -> pattern != null && !pattern.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (Exception ex) {
            log.warn("Invalid allowed_tools configuration, all tools are denied: {}", ex.getMessage());
            return List.of(DENY_ALL);
        }
    }

    public static boolean allows(List<String> allowlist, String fullName, String remoteName) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        for (String pattern : allowlist) {
            if (matchesGlob(fullName, pattern) || matchesGlob(remoteName, pattern)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesGlob(String value, String pattern) {
        if (value == null || Texts.isBlank(pattern)) {
            return false;
        }
        String[] parts = pattern.trim().split("\\*", -1);
        StringBuilder regex = new StringBuilder("(?i)");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return value.matches(regex.toString());
    }
}
