package com.king.ai.compliance;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LocalRuleModerator {

    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern REPEATED = Pattern.compile("(.)\\1{79,}");
    private static final String IGNORABLE = "\u200B\u200C\u200D\uFEFF";
    private static final String SEPARATORS =
            " \t\r\n-_./\\|+*·,，。！？!?：:；;、\"'“”‘’()（）[]【】<>";

    public ModerationScan scan(String text, List<String> blocklist) {
        if (text == null || text.isEmpty()) {
            return ModerationScan.allow();
        }
        Normalized normalized = normalize(text);
        List<ModerationHit> hits = new ArrayList<>();
        for (String term : blocklist) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String needle = normalizeTerm(term);
            if (needle.isEmpty()) {
                continue;
            }
            int index = normalized.compact.indexOf(needle);
            while (index >= 0) {
                int start = normalized.positions.get(index);
                int end = normalized.positions.get(index + needle.length() - 1) + 1;
                hits.add(new ModerationHit("BLOCKLIST", start, end));
                index = normalized.compact.indexOf(needle, index + needle.length());
            }
        }
        if (!hits.isEmpty()) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.BLOCK,
                    "BLOCKLIST", "命中违禁词"), hits);
        }
        Matcher links = URL.matcher(text);
        int count = 0;
        while (links.find()) {
            count++;
        }
        if (count >= 6) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                    "SPAM", "链接数量异常"), List.of());
        }
        if (REPEATED.matcher(text).find()) {
            return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                    "FLOOD", "重复字符异常"), List.of());
        }
        return ModerationScan.allow();
    }

    public String mask(String text, ModerationScan scan) {
        if (text == null || scan == null || scan.hits().isEmpty()) {
            return text == null ? "" : text;
        }
        List<ModerationHit> hits = scan.hits().stream()
                .sorted(java.util.Comparator.comparingInt(ModerationHit::start).reversed())
                .toList();
        StringBuilder builder = new StringBuilder(text);
        for (ModerationHit hit : hits) {
            if (hit.start() >= 0 && hit.end() <= builder.length() && hit.start() < hit.end()) {
                builder.replace(hit.start(), hit.end(), "[已屏蔽]");
            }
        }
        return builder.toString();
    }

    private record Normalized(String compact, List<Integer> positions) {
    }

    private static Normalized normalize(String text) {
        StringBuilder compact = new StringBuilder(text.length());
        List<Integer> positions = new ArrayList<>(text.length());
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (normalized.length() != text.length()) {
            // 命中区间最终用于改原串；长度变化时直接用原串，避免下标错位导致漏屏蔽。
            normalized = text;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (IGNORABLE.indexOf(ch) >= 0 || SEPARATORS.indexOf(ch) >= 0) {
                continue;
            }
            compact.append(Character.toLowerCase(ch));
            positions.add(i);
        }
        return new Normalized(compact.toString(), positions);
    }

    private static String normalizeTerm(String term) {
        Normalized normalized = normalize(term);
        return normalized.compact().toLowerCase(Locale.ROOT);
    }
}
