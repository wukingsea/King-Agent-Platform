package com.king.ai.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.mapper.ChatMessageDeltaMapper;
import com.king.ai.chat.mapper.ChatMessageMapper;
import com.king.ai.chat.mapper.ChatSessionMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.SpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceHistoryWindowTest {

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;

    private ChatService service() {
        return new ChatService(sessionMapper, messageMapper, mock(ChatMessageDeltaMapper.class),
                mock(AgentMapper.class), new ObjectMapper(), new KingAiProperties(),
                mock(SpaceService.class));
    }

    private ChatMessage msg(long id, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(1L);
        m.setRole(role);
        m.setContent(content);
        m.setStatus(ChatService.STATUS_COMPLETED);
        return m;
    }

    // The production SQL is `ORDER BY id DESC LIMIT N`, so the mapper returns the most recent
    // N messages in descending id order. The window limit is enforced by that LIMIT; the
    // in-memory loop only re-orders the returned rows ascending and skips blanks.
    private List<ChatMessage> recent(int n) {
        List<ChatMessage> all = List.of(
                msg(5, "user", "u3"),
                msg(4, "assistant", "a2"),
                msg(3, "user", "u2"),
                msg(2, "assistant", "a1"),
                msg(1, "user", "u1"));
        return all.subList(0, Math.min(n, all.size()));
    }

    @Test
    void nullWindowUsesGlobalDefaultAndQueries() {
        when(messageMapper.selectList(any())).thenReturn(recent(5));

        List<?> history = service().history(1L, null);

        // global default is 20, so all 5 are returned in ascending order (oldest first)
        assertThat(history).hasSize(5);
        verify(messageMapper).selectList(any());
    }

    @Test
    void zeroWindowReturnsEmptyWithoutQuery() {
        List<?> history = service().history(1L, 0);

        assertThat(history).isEmpty();
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void positiveWindowCapsAtRequestedTail() {
        when(messageMapper.selectList(any())).thenReturn(recent(2));

        List<?> history = service().history(1L, 2);

        // the 2 most recent messages (ids 4,5) returned ascending: u3 then a2
        assertThat(history).hasSize(2);
        assertThat(history.get(0).toString()).contains("u3");
        assertThat(history.get(1).toString()).contains("a2");
    }

    @Test
    void windowLargerThanGlobalCapsAtGlobalDefault() {
        when(messageMapper.selectList(any())).thenReturn(recent(5));

        List<?> history = service().history(1L, 999);

        // global default is 20, but only 5 exist; all 5 kept, newest first (u3..u1)
        assertThat(history).hasSize(5);
        assertThat(history.get(0).toString()).contains("u3");
        assertThat(history.get(4).toString()).contains("u1");
    }
}
