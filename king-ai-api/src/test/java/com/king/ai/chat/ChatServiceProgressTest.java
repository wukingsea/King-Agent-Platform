package com.king.ai.chat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.entity.ChatMessageDelta;
import com.king.ai.chat.mapper.ChatMessageDeltaMapper;
import com.king.ai.chat.mapper.ChatMessageMapper;
import com.king.ai.chat.mapper.ChatSessionMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.SpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChatServiceProgressTest {

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private ChatMessageDeltaMapper deltaMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private SpaceService spaceService;

    private ChatService service() {
        return new ChatService(sessionMapper, messageMapper, deltaMapper, agentMapper,
                new ObjectMapper(), new KingAiProperties(), spaceService);
    }

    @Test
    void appendProgressStoresOnlyTheNewFragment() {
        service().appendProgress(42L, " world", "", null, null);

        ArgumentCaptor<ChatMessageDelta> captor = ArgumentCaptor.forClass(ChatMessageDelta.class);
        verify(deltaMapper).insert(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(42L);
        assertThat(captor.getValue().getContentDelta()).isEqualTo(" world");
        assertThat(captor.getValue().getReasoningDelta()).isNull();
        assertThat(captor.getValue().getToolTrace()).isNull();
        assertThat(captor.getValue().getCitations()).isNull();
    }

    @Test
    void appendProgressSkipsEmptyFragments() {
        service().appendProgress(42L, "", "", null, null);

        verifyNoInteractions(deltaMapper);
    }

    @Test
    void finishingMessageDropsProgressDeltas() {
        service().finishAssistant(42L, "full", "reasoning", "[]", "[]",
                ChatService.STATUS_COMPLETED, "model-x", 1, 2, 3L);

        verify(messageMapper).updateById(any(ChatMessage.class));
        verify(deltaMapper).delete(any(Wrapper.class));
    }

    @Test
    void resetProgressRewritesTheMessageAndClearsDeltas() {
        service().resetProgress(42L, "partial", "reasoning");

        verify(messageMapper).updateById(any(ChatMessage.class));
        verify(deltaMapper).delete(any(Wrapper.class));
    }

    @Test
    void noDeltaIsDeletedWhenNothingWasStreamed() {
        service().appendProgress(42L, null, null, null, null);

        verify(deltaMapper, never()).delete(any(Wrapper.class));
    }
}
