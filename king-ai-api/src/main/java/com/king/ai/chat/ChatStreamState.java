package com.king.ai.chat;

import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.compliance.ComplianceStreamGuard;
import com.king.ai.llm.CancellationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单次对话请求的流式状态：SSE 输出、落库进度快照、工具轨迹与引用列表。
 * 由 {@code ChatRuntime} 在请求开始时创建，贯穿整个生成—落库生命周期，
 * 供流式写入、工具编排、重试回滚等协作组件共享读写。
 */
final class ChatStreamState {

    final SseEmitter emitter;
    final CancellationToken token;
    final ComplianceStreamGuard contentGuard;
    final ComplianceStreamGuard reasoningGuard;
    final String source;
    final StreamProgress progress;

    ChatMessage progressMessage;
    final StringBuilder content = new StringBuilder();
    final StringBuilder reasoning = new StringBuilder();
    final List<Map<String, Object>> trace = new ArrayList<>();
    final List<Map<String, Object>> citations = new ArrayList<>();

    ChatStreamState(SseEmitter emitter, CancellationToken token,
                    ComplianceStreamGuard contentGuard, ComplianceStreamGuard reasoningGuard,
                    String source, long startedAt) {
        this.emitter = emitter;
        this.token = token;
        this.contentGuard = contentGuard;
        this.reasoningGuard = reasoningGuard;
        this.source = source;
        this.progress = new StreamProgress(startedAt);
    }

    /** 流式落库进度：只记录尚未写入增量表的文本长度，避免重复追加。 */
    static final class StreamProgress {
        long lastFlushAt;
        int contentLength;
        int reasoningLength;

        StreamProgress(long startedAt) {
            this.lastFlushAt = startedAt;
        }
    }
}
