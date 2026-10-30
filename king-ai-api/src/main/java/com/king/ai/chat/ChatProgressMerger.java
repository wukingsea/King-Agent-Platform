package com.king.ai.chat;

import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.entity.ChatMessageDelta;
import com.king.ai.common.Texts;

import java.util.List;

final class ChatProgressMerger {

    private ChatProgressMerger() {
    }

    static void merge(ChatMessage message, List<ChatMessageDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder(Texts.blankToDefault(message.getContent(), ""));
        StringBuilder reasoning = new StringBuilder(Texts.blankToDefault(message.getReasoning(), ""));
        String toolTrace = message.getToolTrace();
        String citations = message.getCitations();
        for (ChatMessageDelta delta : deltas) {
            if (delta.getContentDelta() != null) {
                content.append(delta.getContentDelta());
            }
            if (delta.getReasoningDelta() != null) {
                reasoning.append(delta.getReasoningDelta());
            }
            if (delta.getToolTrace() != null) {
                toolTrace = delta.getToolTrace();
            }
            if (delta.getCitations() != null) {
                citations = delta.getCitations();
            }
        }
        message.setContent(content.toString());
        message.setReasoning(reasoning.toString());
        if (toolTrace != null) {
            message.setToolTrace(toolTrace);
        }
        if (citations != null) {
            message.setCitations(citations);
        }
    }
}
