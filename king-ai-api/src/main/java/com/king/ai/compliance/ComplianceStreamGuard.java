package com.king.ai.compliance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式输出的脱敏安全带：在内存中滞后 {@code streamHoldbackChars} 个字符再放行，
 * 使跨 delta 拆分的敏感串先被完整匹配、再输出替换结果。
 *
 * <p>缓冲内容从不落库、不进日志；{@link #countsByCategory()} 只暴露规则类别与次数。</p>
 */
public class ComplianceStreamGuard {

    private final RedactionService redactionService;
    private final ModerationService moderationService;
    private final LocalRuleModerator localModerator;
    private final ComplianceSettings settings;
    private final ModerationContext context;
    private final boolean publicChat;
    private final int holdback;
    private final StringBuilder pending = new StringBuilder();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public ComplianceStreamGuard(RedactionService redactionService, ModerationService moderationService,
                                 ComplianceSettings settings, ModerationContext context, boolean publicChat) {
        this.redactionService = redactionService;
        this.moderationService = moderationService;
        // LocalRuleModerator 无状态，这里直接复用其屏蔽实现，避免改动已交付的 ModerationService 接口。
        this.localModerator = new LocalRuleModerator();
        this.settings = settings;
        this.context = context;
        this.publicChat = publicChat;
        this.holdback = Math.max(32, settings.streamHoldbackChars());
    }

    public String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        pending.append(delta);
        // 先脱敏再送审：EXTERNAL/HYBRID 模式会把文本发给外部审核服务，原文中的
        // PII 不能因此离开部署边界；审核命中区间也统一以脱敏后的文本为准。
        RedactionResult scanRedaction = redactionService.redact(pending.toString(), settings);
        ModerationScan scan = moderationService.scan(scanRedaction.text(), settings, context);
        // 公开对话必须 fail-closed：REVIEW 不能因为 outputAction=WARN 而放行。
        if (mustBlock(scan)) {
            throw new ContentModerationException(scan.decision().category());
        }
        boolean masked = false;
        if (!scan.hits().isEmpty() && settings.outputAction() == ModerationAction.WARN) {
            pending.setLength(0);
            pending.append(localModerator.mask(scanRedaction.text(), scan));
            // 缓冲区已被脱敏文本替换，这些 PII 命中不会再出现在后续放行窗口中，
            // 必须在替换时计入，否则该 pass 的脱敏计数会永久丢失。
            mergeCounts(scanRedaction.hits());
            masked = true;
        }
        if (pending.length() <= holdback) {
            return "";
        }
        // 命中区间相对脱敏前的缓冲区；屏蔽分支已把整段缓冲区替换成脱敏+屏蔽后的文本，
        // 旧区间不再适用，且该文本已不含敏感串，无需回退。
        int emitEnd = safeEmitEnd(pending.length() - holdback,
                masked ? List.of() : scanRedaction.hits());
        if (emitEnd <= 0) {
            return "";
        }
        String window = pending.substring(0, emitEnd);
        RedactionResult result = redactionService.redact(window, settings);
        mergeCounts(result.hits());
        pending.delete(0, emitEnd);
        return result.text();
    }

    public String flush() {
        if (pending.length() == 0) {
            return "";
        }
        RedactionResult scanRedaction = redactionService.redact(pending.toString(), settings);
        ModerationScan scan = moderationService.scan(scanRedaction.text(), settings, context);
        if (mustBlock(scan)) {
            throw new ContentModerationException(scan.decision().category());
        }
        boolean masked = false;
        String text = pending.toString();
        if (!scan.hits().isEmpty() && settings.outputAction() == ModerationAction.WARN) {
            // 命中区间属于 scanText，必须用同一份文本做替换。
            text = localModerator.mask(scanRedaction.text(), scan);
            masked = true;
        }
        RedactionResult result = redactionService.redact(text, settings);
        // 屏蔽分支的输出已脱敏，最终这次 redact 观察不到 PII；计入首次扫描的命中，
        // 保证同一 pass 内每个命中恰好计一次。非屏蔽分支两者等价，以最终结果为准。
        mergeCounts(masked ? scanRedaction.hits() : result.hits());
        pending.setLength(0);
        return result.text();
    }

    public void reset() {
        pending.setLength(0);
        counts.clear();
    }

    /**
     * Snapshot of the allow-window state (un-emitted holdback tail and redaction counts).
     * It exists only for retry rollback inside one request: it is never persisted, logged,
     * or emitted, and the buffered text is never exposed.
     */
    public record Checkpoint(String pending, Map<String, Integer> counts) {
        public Checkpoint {
            pending = pending == null ? "" : pending;
            counts = Map.copyOf(counts == null ? Map.of() : counts);
        }
    }

    /**
     * Captures the current pending tail and counts so a failed streaming attempt can be rolled
     * back without discarding what earlier tool rounds already accumulated. The snapshot stays
     * in memory for the lifetime of one request only.
     */
    public Checkpoint checkpoint() {
        return new Checkpoint(pending.toString(), counts);
    }

    /**
     * Restores a snapshot taken by {@link #checkpoint()}; {@code null} clears the guard.
     * The buffered text is neither exposed nor logged.
     */
    public void restore(Checkpoint checkpoint) {
        pending.setLength(0);
        counts.clear();
        if (checkpoint == null) {
            return;
        }
        pending.append(checkpoint.pending());
        counts.putAll(checkpoint.counts());
    }

    public Map<String, Integer> countsByCategory() {
        return Map.copyOf(counts);
    }

    private boolean mustBlock(ModerationScan scan) {
        return scan.decision().blocking(publicChat)
                && (settings.outputAction() == ModerationAction.BLOCK
                || (publicChat && scan.decision().verdict() == ModerationVerdict.REVIEW));
    }

    private void mergeCounts(List<RedactionHit> hits) {
        for (RedactionHit hit : hits) {
            counts.merge(hit.category(), 1, Integer::sum);
        }
    }

    /**
     * 放行边界不能落在已识别敏感串的中间：命中区间若不慎被切开，前半段会以原文输出，
     * 后半段再也无法单独匹配。命中从缓冲区开头开始时无法回退，改为连同整个命中一起
     * 放行（替换为标记），否则缓冲区会为等待“更安全的边界”无限堆积。
     */
    private static int safeEmitEnd(int emitEnd, List<RedactionHit> hits) {
        for (RedactionHit hit : hits) {
            if (hit.start() < emitEnd && emitEnd < hit.end()) {
                return hit.start() > 0 ? hit.start() : hit.end();
            }
        }
        return emitEnd;
    }
}
