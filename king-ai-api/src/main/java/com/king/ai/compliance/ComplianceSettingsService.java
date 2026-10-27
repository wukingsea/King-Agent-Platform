package com.king.ai.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import com.king.ai.compliance.entity.ComplianceSetting;
import com.king.ai.compliance.mapper.ComplianceSettingMapper;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceSettingsService {

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int MAX_BLOCKLIST_TERMS = 500;
    private static final int MAX_TERM_CHARS = 50;

    private final ComplianceSettingMapper mapper;
    private final KingAiProperties properties;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ComplianceSettings settings, long expiresAt) {
    }

    public ComplianceSettings effective(Long spaceId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(spaceId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.settings();
        }
        ComplianceSettings settings = load(spaceId);
        cache.put(spaceId, new Cached(settings, now + CACHE_TTL_MS));
        return settings;
    }

    public ComplianceSettingView view(Long spaceId) {
        ComplianceSetting row = findRow(spaceId);
        if (row == null) {
            ComplianceSettings defaults = effective(spaceId);
            return new ComplianceSettingView(defaults.piiEnabled(),
                    defaults.moderationMode().name(), defaults.inputAction().name(),
                    defaults.outputAction().name(),
                    defaults.blocklist(), List.of());
        }
        return new ComplianceSettingView(row.getPiiEnabled() != null && row.getPiiEnabled() == 1,
                row.getModerationMode(), row.getModerationInputAction(),
                row.getModerationOutputAction(), splitLines(row.getBlocklist()),
                splitLines(row.getPiiCustomPatterns()));
    }

    @Transactional
    public ComplianceSettingView update(Long spaceId, ComplianceSettingUpdateRequest request) {
        ModerationMode mode = parseMode(request.moderationMode());
        ModerationAction inputAction = parseAction(request.inputAction());
        ModerationAction outputAction = parseAction(request.outputAction());
        List<String> terms = normalizeTerms(request.blocklist());
        List<String> patterns = normalizePatterns(request.customPiiPatterns());

        ComplianceSetting row = findRow(spaceId);
        boolean insert = row == null;
        if (insert) {
            row = new ComplianceSetting();
            row.setSpaceId(spaceId);
        }
        row.setPiiEnabled(Boolean.TRUE.equals(request.piiEnabled()) ? 1 : 0);
        row.setModerationMode(mode.name());
        row.setModerationInputAction(inputAction.name());
        row.setModerationOutputAction(outputAction.name());
        row.setBlocklist(String.join("\n", terms));
        row.setPiiCustomPatterns(String.join("\n", patterns));
        if (insert) {
            mapper.insert(row);
        } else {
            OptimisticLocks.requireUpdated(mapper.updateById(row), "合规配置");
        }
        cache.remove(spaceId);
        return view(spaceId);
    }

    public void evict(Long spaceId) {
        cache.remove(spaceId);
    }

    private ComplianceSettings load(Long spaceId) {
        ComplianceSetting row = findRow(spaceId);
        if (row == null) {
            return new ComplianceSettings(
                    properties.getCompliance().getPii().isDefaultEnabled(),
                    properties.getCompliance().getModeration().getDefaultMode(),
                    ModerationAction.BLOCK, ModerationAction.BLOCK,
                    List.of(), List.of(), holdback());
        }
        return new ComplianceSettings(
                row.getPiiEnabled() != null && row.getPiiEnabled() == 1,
                parseModeOrDefault(row.getModerationMode()),
                ModerationAction.parse(row.getModerationInputAction()),
                ModerationAction.parse(row.getModerationOutputAction()),
                splitLines(row.getBlocklist()),
                compileQuietly(splitLines(row.getPiiCustomPatterns())),
                holdback());
    }

    private ComplianceSetting findRow(Long spaceId) {
        return mapper.selectOne(new LambdaQueryWrapper<ComplianceSetting>()
                .eq(ComplianceSetting::getSpaceId, spaceId).last("LIMIT 1"));
    }

    private int holdback() {
        return Math.min(1024, Math.max(32,
                properties.getCompliance().getPii().getStreamHoldbackChars()));
    }

    private ModerationMode parseModeOrDefault(String value) {
        try {
            return ModerationMode.parse(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid moderation mode in database, falling back to OFF: {}", value);
            return ModerationMode.OFF;
        }
    }

    private ModerationMode parseMode(String value) {
        if (Texts.isBlank(value)) {
            throw BizException.badRequest("审核模式不能为空");
        }
        try {
            return ModerationMode.parse(value);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("审核模式仅支持 OFF/LOCAL/EXTERNAL/HYBRID");
        }
    }

    private ModerationAction parseAction(String value) {
        if (Texts.isBlank(value)) {
            throw BizException.badRequest("审核动作不能为空");
        }
        try {
            return ModerationAction.parse(value);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("审核动作仅支持 BLOCK/WARN");
        }
    }

    private List<String> normalizeTerms(String raw) {
        Set<String> terms = new LinkedHashSet<>();
        if (!Texts.isBlank(raw)) {
            for (String part : raw.split("[\\n,，]")) {
                String term = part.trim();
                if (term.isEmpty()) {
                    continue;
                }
                if (term.length() > MAX_TERM_CHARS) {
                    throw BizException.badRequest("违禁词长度不能超过 " + MAX_TERM_CHARS + " 个字符");
                }
                terms.add(term);
            }
        }
        if (terms.size() > MAX_BLOCKLIST_TERMS) {
            throw BizException.badRequest("违禁词不能超过 " + MAX_BLOCKLIST_TERMS + " 条");
        }
        return new ArrayList<>(terms);
    }

    private List<String> normalizePatterns(String raw) {
        Set<String> patterns = new LinkedHashSet<>();
        if (!Texts.isBlank(raw)) {
            for (String part : raw.split("\\n")) {
                String pattern = part.trim();
                if (pattern.isEmpty()) {
                    continue;
                }
                if (pattern.length() > 200) {
                    throw BizException.badRequest("自定义正则以 200 字符为限");
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    throw BizException.badRequest("自定义 PII 正则第 " + (patterns.size() + 1) + " 条不合法");
                }
                patterns.add(pattern);
            }
        }
        int max = Math.max(1, properties.getCompliance().getPii().getMaxCustomRules());
        if (patterns.size() > max) {
            throw BizException.badRequest("自定义 PII 正则不能超过 " + max + " 条");
        }
        return new ArrayList<>(patterns);
    }

    private List<Pattern> compileQuietly(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException ex) {
                log.warn("Ignoring invalid stored PII pattern");
            }
        }
        return compiled;
    }

    private static List<String> splitLines(String value) {
        if (Texts.isBlank(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("\\n"))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }
}
