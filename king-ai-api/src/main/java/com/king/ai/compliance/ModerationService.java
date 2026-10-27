package com.king.ai.compliance;

import com.king.ai.config.KingAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ModerationService {

    private final LocalRuleModerator local;
    private final KingAiProperties properties;
    private final ExternalModerationClient external;

    public ModerationService(LocalRuleModerator local, KingAiProperties properties,
                             ExternalModerationClient external) {
        this.local = local;
        this.properties = properties;
        this.external = external;
    }

    public ModerationScan scan(String text, ComplianceSettings settings, ModerationContext context) {
        if (settings == null || !settings.moderationEnabled()) {
            return ModerationScan.allow();
        }
        ModerationScan localScan = settings.moderationMode().usesLocal()
                ? local.scan(text, settings.blocklist()) : ModerationScan.allow();
        if (localScan.decision().verdict() == ModerationVerdict.BLOCK) {
            return localScan;
        }
        if (!settings.moderationMode().usesExternal()) {
            return localScan;
        }
        try {
            ModerationDecision decision = external.check(text, context);
            if (decision.verdict() == ModerationVerdict.BLOCK) {
                return new ModerationScan(decision, List.of());
            }
            if (decision.verdict() == ModerationVerdict.REVIEW
                    && localScan.decision().verdict() == ModerationVerdict.ALLOW) {
                return new ModerationScan(decision, List.of());
            }
            return localScan;
        } catch (RuntimeException ex) {
            log.warn("External moderation unavailable: {}", ex.getMessage());
            ModerationScan fallback = localScan;
            if (fallback.decision().verdict() == ModerationVerdict.ALLOW
                    && !settings.moderationMode().usesLocal()) {
                // EXTERNAL 模式未预先跑本地规则；降级时补跑一次，作为本地兜底结果。
                fallback = local.scan(text, settings.blocklist());
            }
            if (fallback.decision().verdict() != ModerationVerdict.ALLOW) {
                return fallback;
            }
            if (context.publicChat() && properties.getCompliance().getModeration().isFailClosedOnError()) {
                return new ModerationScan(new ModerationDecision(ModerationVerdict.REVIEW,
                        "MODERATION_UNAVAILABLE", "审核服务不可用"), List.of());
            }
            return fallback;
        }
    }
}
