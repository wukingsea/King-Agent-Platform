package com.king.ai.chat;

import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.entity.ChatMessageDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatProgressMergerTest {

    @Test
    void appendsContentAndReasoningDeltasInOrder() {
        ChatMessage message = new ChatMessage();
        message.setContent("hello");
        message.setReasoning("step 1");
        message.setToolTrace("[]");
        message.setCitations("[]");

        ChatProgressMerger.merge(message, List.of(
                delta(" world", " then", null, null),
                delta("!", "", "[{\"name\":\"tool\"}]", null),
                delta("", "", null, "[{\"index\":1}]")));

        assertThat(message.getContent()).isEqualTo("hello world!");
        assertThat(message.getReasoning()).isEqualTo("step 1 then");
        assertThat(message.getToolTrace()).isEqualTo("[{\"name\":\"tool\"}]");
        assertThat(message.getCitations()).isEqualTo("[{\"index\":1}]");
    }

    @Test
    void keepsBaseMessageWhenThereAreNoDeltas() {
        ChatMessage message = new ChatMessage();
        message.setContent("done");

        ChatProgressMerger.merge(message, List.of());

        assertThat(message.getContent()).isEqualTo("done");
    }

    private ChatMessageDelta delta(String content, String reasoning, String toolTrace, String citations) {
        ChatMessageDelta delta = new ChatMessageDelta();
        delta.setContentDelta(content);
        delta.setReasoningDelta(reasoning);
        delta.setToolTrace(toolTrace);
        delta.setCitations(citations);
        return delta;
    }
}
