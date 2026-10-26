package com.king.ai.chat;

import com.king.ai.agent.AgentRuntime;
import com.king.ai.agent.AgentService;
import com.king.ai.audit.AuditLogService;
import com.king.ai.chat.dto.ChatMessageView;
import com.king.ai.chat.dto.ChatSessionView;
import com.king.ai.chat.dto.ChatStopResult;
import com.king.ai.chat.dto.PublicChatSendRequest;
import com.king.ai.chat.dto.ToolApprovalRequest;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.BizException;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.config.KingAiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/public/chat")
@RequiredArgsConstructor
public class PublicChatController {

    private final ChatService chatService;
    private final ChatRuntime chatRuntime;
    private final AgentService agentService;
    private final ChatCancellationRegistry cancellationRegistry;
    private final ToolApprovalRegistry toolApprovalRegistry;
    private final VisitorTokenService visitorTokenService;
    private final PublicChatRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final KingAiProperties properties;
    private final AuditLogService auditLogService;

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionView>> sessions(@RequestParam String publicId,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(request, response);
        AgentRuntime agent = agentService.publishedRuntime(publicId);
        return ApiResponse.ok(chatService.visitorSessions(agent.agentId(), visitor));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageView>> messages(@RequestParam String publicId,
                                                       @PathVariable Long sessionId,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(request, response);
        AgentRuntime agent = agentService.publishedRuntime(publicId);
        return ApiResponse.ok(chatService.visitorMessages(agent.agentId(), visitor, sessionId));
    }

    @PostMapping("/send")
    public SseEmitter send(@Valid @RequestBody PublicChatSendRequest request,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) {
        String visitor = visitorTokenService.requireVisitor(httpRequest, httpResponse);
        AgentRuntime agent = agentService.publishedRuntime(request.publicId());
        rateLimiter.check(visitor + "|" + clientIpResolver.resolve(httpRequest),
                properties.getPublicChat().getRateLimitPerMinute());
        return chatRuntime.start(ChatActor.visitor(visitor, agent.spaceId()), agent,
                request.sessionId(), request.message(), ChatService.SOURCE_PUBLIC);
    }

    @PostMapping("/requests/{requestId}/stop")
    public ApiResponse<ChatStopResult> stop(@PathVariable String requestId,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(request, response);
        return ApiResponse.ok(new ChatStopResult(
                cancellationRegistry.cancel(requestId, null, "visitor:" + visitor, false)));
    }

    @PostMapping("/requests/{requestId}/tool-approvals/{callId}")
    public ApiResponse<Boolean> approveTool(@PathVariable String requestId,
                                            @PathVariable String callId,
                                            @Valid @RequestBody ToolApprovalRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(httpRequest, response);
        ToolApprovalRegistry.Pending pending = toolApprovalRegistry.decide(requestId, callId, null,
                "visitor:" + visitor, false, request.approved());
        if (pending == null) {
            throw BizException.notFound("待确认的工具调用不存在或已过期");
        }
        auditLogService.recordChat(pending.spaceId(), null, "visitor:" + visitor, "chat.tool_approval",
                requestId + ":" + callId,
                request.approved() ? AuditLogService.STATUS_SUCCESS : AuditLogService.STATUS_DENIED,
                (request.approved() ? "批准" : "拒绝") + "工具调用 " + pending.toolName()
                        + "（来源：" + pending.source() + "）",
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"), requestId);
        return ApiResponse.ok(request.approved());
    }
}
