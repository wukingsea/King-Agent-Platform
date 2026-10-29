package com.king.ai.chat;

import com.king.ai.compliance.ComplianceStreamGuard;

/**
 * 一次模型调用的输出快照：降级前回滚到本轮开始，既保留此前工具轮次已输出的内容，
 * 又避免主模型的半截输出与降级模型的完整输出拼接。
 */
record ChatStreamCheckpoint(int contentLength, int reasoningLength,
                            ComplianceStreamGuard.Checkpoint contentCheckpoint,
                            ComplianceStreamGuard.Checkpoint reasoningCheckpoint) {

    static ChatStreamCheckpoint capture(StringBuilder content, StringBuilder reasoning,
                                        ComplianceStreamGuard contentGuard,
                                        ComplianceStreamGuard reasoningGuard) {
        return new ChatStreamCheckpoint(content.length(), reasoning.length(),
                contentGuard.checkpoint(), reasoningGuard.checkpoint());
    }

    void restore(StringBuilder content, StringBuilder reasoning,
                 ComplianceStreamGuard contentGuard, ComplianceStreamGuard reasoningGuard) {
        content.setLength(contentLength);
        reasoning.setLength(reasoningLength);
        contentGuard.restore(contentCheckpoint);
        reasoningGuard.restore(reasoningCheckpoint);
    }
}
