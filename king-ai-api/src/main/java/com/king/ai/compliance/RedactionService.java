package com.king.ai.compliance;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RedactionService {

    private static final int MAX_SCAN_CHARS = 200_000;

    private static final List<RedactionRule> BUILT_IN = List.of(
            new RedactionRule("email", "EMAIL",
                    Pattern.compile("(?i)(?<![\\w.%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])"),
                    value -> true, "[REDACTED:EMAIL]"),
            new RedactionRule("cn-mobile", "PHONE",
                    Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
                    value -> true, "[REDACTED:PHONE]"),
            new RedactionRule("cn-id", "ID_CARD",
                    Pattern.compile("(?<![\\dXx])[1-9]\\d{5}(?:19|20)\\d{2}"
                            + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![\\dXx])"),
                    RedactionService::validIdCard, "[REDACTED:ID_CARD]"),
            new RedactionRule("bank-card", "BANK_CARD",
                    Pattern.compile("(?<!\\d)(?:62\\d{14,17}|4\\d{15}|5[1-5]\\d{14})(?!\\d)"),
                    RedactionService::luhn, "[REDACTED:BANK_CARD]"),
            new RedactionRule("api-key", "API_KEY",
                    Pattern.compile("(?i)(?<![A-Za-z0-9])(?:sk|ak|pk|ghp|gho|github_pat|xox[abprs])"
                            + "[_-][A-Za-z0-9_-]{16,}(?![A-Za-z0-9])"),
                    value -> true, "[REDACTED:API_KEY]"),
            new RedactionRule("bearer", "BEARER",
                    Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
                    value -> true, "[REDACTED:BEARER]"),
            new RedactionRule("ipv4", "IP",
                    Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])"),
                    RedactionService::validIpv4, "[REDACTED:IP]")
    );

    public RedactionResult redact(String text, ComplianceSettings settings) {
        if (text == null || text.isEmpty() || settings == null || !settings.piiEnabled()) {
            return new RedactionResult(text == null ? "" : text, List.of());
        }
        String scan = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        List<RedactionRule> rules = new ArrayList<>(BUILT_IN);
        int index = 0;
        for (Pattern pattern : settings.customPiiPatterns()) {
            index++;
            rules.add(new RedactionRule("custom-" + index, "CUSTOM", pattern, value -> true,
                    "[REDACTED:CUSTOM]"));
        }
        List<RedactionHit> hits = findHits(scan, rules);
        if (hits.isEmpty()) {
            return new RedactionResult(text, List.of());
        }
        StringBuilder output = new StringBuilder(scan.length());
        int cursor = 0;
        for (RedactionHit hit : hits) {
            output.append(scan, cursor, hit.start());
            output.append(replacement(rules, hit));
            cursor = hit.end();
        }
        output.append(scan, cursor, scan.length());
        if (text.length() > MAX_SCAN_CHARS) {
            output.append(text, MAX_SCAN_CHARS, text.length());
        }
        return new RedactionResult(output.toString(), hits);
    }

    private static String replacement(List<RedactionRule> rules, RedactionHit hit) {
        return rules.stream().filter(rule -> rule.id().equals(hit.ruleId()))
                .findFirst().map(RedactionRule::replacement).orElse("[REDACTED]");
    }

    private static List<RedactionHit> findHits(String text, List<RedactionRule> rules) {
        List<RedactionHit> candidates = new ArrayList<>();
        for (RedactionRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                if (!rule.accepts(matcher.group())) {
                    continue;
                }
                candidates.add(new RedactionHit(rule.id(), rule.category(),
                        matcher.start(), matcher.end()));
            }
        }
        candidates.sort(Comparator.comparingInt(RedactionHit::start)
                .thenComparing(Comparator.comparingInt((RedactionHit hit) -> hit.end() - hit.start()).reversed()));
        List<RedactionHit> selected = new ArrayList<>();
        int cursor = -1;
        for (RedactionHit candidate : candidates) {
            if (candidate.start() >= cursor) {
                selected.add(candidate);
                cursor = candidate.end();
            }
        }
        return selected;
    }

    private static boolean luhn(String value) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = value.length() - 1; i >= 0; i--) {
            int digit = value.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static boolean validIdCard(String value) {
        if (value.length() != 18) {
            return false;
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
            sum += (ch - '0') * weights[i];
        }
        return Character.toUpperCase(value.charAt(17)) == codes[sum % 11];
    }

    private static boolean validIpv4(String value) {
        String[] parts = value.split("\\.");
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return parts.length == 4;
    }
}
