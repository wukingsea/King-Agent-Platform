package com.king.ai.compliance;

import org.springframework.stereotype.Service;

/**
 * 对话输入合规准备：先脱敏 PII，再按空间策略审核；公开对话命中 REVIEW 时 fail-closed。
 * 返回的文本可直接用于落库、检索与送模型，原始文本不离开本方法。
 */
@Service
public class ChatInputCompliance {

    public record PreparedInput(String text, int redactedCount, boolean masked) {
    }

    private final RedactionService redactionService;
    private final ModerationService moderationService;
    private final LocalRuleModerator localModerator;

    public ChatInputCompliance(RedactionService redactionService, ModerationService moderationService,
                               LocalRuleModerator localModerator) {
        this.redactionService = redactionService;
        this.moderationService = moderationService;
        this.localModerator = localModerator;
    }

    public PreparedInput prepare(String raw, ComplianceSettings settings, ModerationContext context) {
        String text = raw == null ? "" : raw;
        RedactionResult redacted = redactionService.redact(text, settings);
        text = redacted.text();
        ModerationScan scan = moderationService.scan(text, settings, context);
        boolean blocked = scan.decision().blocking(context.publicChat());
        // 公开对话必须 fail-closed：REVIEW 不能因为 inputAction=WARN 而放行。
        if (blocked && (settings.inputAction() == ModerationAction.BLOCK
                || (context.publicChat() && scan.decision().verdict() == ModerationVerdict.REVIEW))) {
            throw new ContentModerationException(scan.decision().category());
        }
        boolean masked = false;
        if (!scan.hits().isEmpty()) {
            text = localModerator.mask(text, scan);
            masked = true;
        }
        return new PreparedInput(text, redacted.redactedCount(), masked);
    }
}
