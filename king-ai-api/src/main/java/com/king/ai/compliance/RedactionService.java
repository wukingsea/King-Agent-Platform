package com.king.ai.compliance;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RedactionService {

    private static final int MAX_SCAN_CHARS = 200_000;

    private static final long MAX_REGEX_STEPS = 5_000_000L;

    private static final ComplianceSettings DEFAULT_SETTINGS = new ComplianceSettings(
            true, ModerationMode.OFF, ModerationAction.BLOCK, ModerationAction.BLOCK,
            List.of(), List.of(), 128);

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
        if (text == null || text.isEmpty()) {
            return new RedactionResult("", List.of());
        }
        ComplianceSettings effective = settings == null ? DEFAULT_SETTINGS : settings;
        if (!effective.piiEnabled()) {
            return new RedactionResult(text, List.of());
        }
        if (text.length() > MAX_SCAN_CHARS) {
            return new RedactionResult("[REDACTED:OVERSIZE]",
                    List.of(new RedactionHit("oversize", "OVERSIZE", 0, text.length())));
        }
        List<RedactionRule> rules = new ArrayList<>(BUILT_IN);
        int customRuleCount = 0;
        for (Pattern pattern : effective.customPiiPatterns()) {
            customRuleCount++;
            if (hasUnboundedNestedQuantifier(pattern)) {
                return regexLimitResult(text);
            }
            rules.add(new RedactionRule("custom-" + customRuleCount, "CUSTOM", pattern, value -> true,
                    "[REDACTED:CUSTOM]"));
        }
        List<RedactionHit> hits;
        try {
            hits = findHits(text, rules, customRuleCount);
        } catch (RegexBudgetExceededException ex) {
            return regexLimitResult(text);
        }
        if (hits.isEmpty()) {
            return new RedactionResult(text, List.of());
        }
        StringBuilder output = new StringBuilder(text.length());
        int cursor = 0;
        for (RedactionHit hit : hits) {
            output.append(text, cursor, hit.start());
            output.append(replacement(rules, hit));
            cursor = hit.end();
        }
        output.append(text, cursor, text.length());
        return new RedactionResult(output.toString(), hits);
    }

    private static String replacement(List<RedactionRule> rules, RedactionHit hit) {
        return rules.stream().filter(rule -> rule.id().equals(hit.ruleId()))
                .findFirst().map(RedactionRule::replacement).orElse("[REDACTED]");
    }

    private static RedactionResult regexLimitResult(String text) {
        return new RedactionResult("[REDACTED:REGEX_LIMIT]",
                List.of(new RedactionHit("regex-limit", "REGEX_LIMIT", 0, text.length())));
    }

    /**
     * Rejects custom patterns whose worst case is unbounded: an unbounded quantifier applied to a
     * group that itself contains an unbounded quantifier. The JDK regex engine resolves some of
     * these shapes quickly, but the class is a classic super-linear backtracking hazard and a
     * fail-closed scan must not depend on engine-specific optimizations.
     */
    static boolean hasUnboundedNestedQuantifier(Pattern pattern) {
        String regex = pattern.pattern();
        Deque<Boolean> groupUnbounded = new ArrayDeque<>();
        Deque<Boolean> atomicGroup = new ArrayDeque<>();
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c == '(') {
                atomicGroup.push(regex.startsWith("(?>", i));
                groupUnbounded.push(false);
                continue;
            }
            if (c == ')') {
                if (groupUnbounded.isEmpty()) {
                    continue;
                }
                boolean unbounded = groupUnbounded.pop();
                boolean atomic = atomicGroup.pop();
                int next = i + 1;
                if (next < regex.length() && isUnboundedQuantifierStart(regex, next)) {
                    int end = quantifierEnd(regex, next);
                    boolean possessive = end < regex.length() && regex.charAt(end) == '+';
                    if (!possessive && !atomic && unbounded) {
                        return true;
                    }
                }
                if (!atomic && unbounded && !groupUnbounded.isEmpty()) {
                    groupUnbounded.pop();
                    groupUnbounded.push(true);
                }
                continue;
            }
            if (c == '*' || c == '+') {
                if (i + 1 < regex.length() && regex.charAt(i + 1) == '+') {
                    i++;
                    continue;
                }
                markUnbounded(groupUnbounded);
                continue;
            }
            if (c == '{') {
                int close = regex.indexOf('}', i + 1);
                if (close > 0) {
                    String body = regex.substring(i + 1, close);
                    if (body.matches("\\d+(,\\d*)?")) {
                        if (body.endsWith(",")) {
                            boolean possessive = close + 1 < regex.length() && regex.charAt(close + 1) == '+';
                            if (!possessive) {
                                markUnbounded(groupUnbounded);
                            }
                            if (possessive) {
                                close++;
                            }
                        }
                        i = close;
                    }
                }
            }
        }
        return false;
    }

    private static void markUnbounded(Deque<Boolean> groupUnbounded) {
        if (groupUnbounded.isEmpty()) {
            return;
        }
        groupUnbounded.pop();
        groupUnbounded.push(true);
    }

    private static boolean isUnboundedQuantifierStart(String regex, int start) {
        char c = regex.charAt(start);
        if (c == '*' || c == '+') {
            return true;
        }
        if (c != '{') {
            return false;
        }
        int close = regex.indexOf('}', start + 1);
        return close > 0 && regex.substring(start + 1, close).matches("\\d+,");
    }

    private static int quantifierEnd(String regex, int start) {
        char c = regex.charAt(start);
        if (c == '*' || c == '+') {
            return start + 1;
        }
        int close = regex.indexOf('}', start + 1);
        return close < 0 ? start + 1 : close + 1;
    }

    private static List<RedactionHit> findHits(String text, List<RedactionRule> rules, int customRuleCount) {
        int builtInCount = rules.size() - customRuleCount;
        List<RedactionHit> candidates = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            RedactionRule rule = rules.get(i);
            Matcher matcher = i < builtInCount
                    ? rule.pattern().matcher(text)
                    : rule.pattern().matcher(new BudgetedCharSequence(text));
            while (matcher.find()) {
                if (matcher.group().isEmpty()) {
                    continue;
                }
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

    private static final class BudgetedCharSequence implements CharSequence {

        private final CharSequence delegate;

        private long steps;

        private BudgetedCharSequence(CharSequence delegate) {
            this.delegate = delegate;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps > MAX_REGEX_STEPS) {
                throw new RegexBudgetExceededException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static final class RegexBudgetExceededException extends RuntimeException {

        private RegexBudgetExceededException() {
            super(null, null, false, false);
        }
    }
}
