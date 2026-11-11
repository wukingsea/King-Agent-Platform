package com.king.ai.chat;

import com.king.ai.chat.ChatStreamState.StreamProgress;
import com.king.ai.chat.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 将流式生成的增量内容周期性落库（assistant 占位消息的 progress 通道），
 * 并支持模型降级/重试时回滚到指定位置，保证前端与落库内容一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProgressFlusher {

    private static final long FLUSH_INTERVAL_MS = 800L;

    private final ChatService chatService;
    private final ChatSseWriter chatSseWriter;

    public void maybeFlush(ChatStreamState state, boolean force) {
        ChatMessage assistant = state.progressMessage;
        if (assistant == null) {
            return;
        }
        StreamProgress progress = state.progress;
        long now = System.currentTimeMillis();
        if (!force && now - progress.lastFlushAt < FLUSH_INTERVAL_MS) {
            return;
        }
        int contentLength = state.content.length();
        int reasoningLength = state.reasoning.length();
        String contentDelta = contentLength > progress.contentLength
                ? state.content.substring(progress.contentLength) : "";
        String reasoningDelta = reasoningLength > progress.reasoningLength
                ? state.reasoning.substring(progress.reasoningLength) : "";
        if (!force && contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
            return;
        }
        progress.lastFlushAt = now;
        try {
            chatService.appendProgress(assistant.getId(), contentDelta, reasoningDelta,
                    force ? chatSseWriter.toolTraceJson(state.trace) : null,
                    force ? chatSseWriter.citationsJson(state.citations) : null);
            progress.contentLength = contentLength;
            progress.reasoningLength = reasoningLength;
        } catch (RuntimeException ex) {
            log.warn("Failed to flush chat progress for message {}: {}", assistant.getId(), ex.getMessage());
        }
    }

    public void rollbackProgress(ChatStreamState state, int contentLength, int reasoningLength) {
        StreamProgress progress = state.progress;
        progress.contentLength = contentLength;
        progress.reasoningLength = reasoningLength;
        ChatMessage assistant = state.progressMessage;
        if (assistant == null) {
            return;
        }
        try {
            chatService.resetProgress(assistant.getId(),
                    state.content.substring(0, contentLength),
                    state.reasoning.substring(0, reasoningLength));
        } catch (RuntimeException ex) {
            log.warn("Failed to reset chat progress for message {}: {}", assistant.getId(), ex.getMessage());
        }
    }
}
