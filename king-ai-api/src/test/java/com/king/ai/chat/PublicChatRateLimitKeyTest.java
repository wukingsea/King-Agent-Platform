package com.king.ai.chat;

import com.king.ai.agent.AgentRuntime;
import com.king.ai.agent.AgentService;
import com.king.ai.audit.AuditLogService;
import com.king.ai.chat.dto.PublicChatSendRequest;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicChatRateLimitKeyTest {

    @Test
    void rateLimitIsChargedToBothTheIpAndTheVisitor() {
        ChatService chatService = mock(ChatService.class);
        ChatRuntime chatRuntime = mock(ChatRuntime.class);
        AgentService agentService = mock(AgentService.class);
        ChatCancellationRegistry cancellationRegistry = mock(ChatCancellationRegistry.class);
        ToolApprovalRegistry toolApprovalRegistry = mock(ToolApprovalRegistry.class);
        VisitorTokenService visitorTokenService = mock(VisitorTokenService.class);
        PublicChatRateLimiter rateLimiter = mock(PublicChatRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        KingAiProperties properties = new KingAiProperties();
        properties.getPublicChat().setRateLimitPerMinute(30);

        when(visitorTokenService.requireVisitor(any(), any())).thenReturn("visitor-1");
        when(agentService.publishedRuntime("pub-1", "visitor-1")).thenReturn(mock(AgentRuntime.class));
        when(clientIpResolver.resolve(any())).thenReturn("198.51.100.23");

        PublicChatController controller = new PublicChatController(chatService, chatRuntime,
                agentService, cancellationRegistry, toolApprovalRegistry, visitorTokenService,
                rateLimiter, clientIpResolver, properties, auditLogService);

        controller.send(new PublicChatSendRequest("pub-1", null, "hello"),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(rateLimiter).check("ip|198.51.100.23", 30);
        verify(rateLimiter).check("visitor|visitor-1", 30);
    }
}
