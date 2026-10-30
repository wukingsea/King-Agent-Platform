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
        checkRate(request, visitor);
        AgentRuntime agent = agentService.publishedRuntime(publicId, visitor);
        return ApiResponse.ok(chatService.visitorSessions(agent.agentId(), visitor));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageView>> messages(@RequestParam String publicId,
                                                       @PathVariable Long sessionId,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(request, response);
        checkRate(request, visitor);
        AgentRuntime agent = agentService.publishedRuntime(publicId, visitor);
        return ApiResponse.ok(chatService.visitorMessages(agent.agentId(), visitor, sessionId));
    }

    @PostMapping("/send")
    public SseEmitter send(@Valid @RequestBody PublicChatSendRequest request,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) {
        String visitor = visitorTokenService.requireVisitor(httpRequest, httpResponse);
        checkRate(httpRequest, visitor);
        AgentRuntime agent = agentService.publishedRuntime(request.publicId(), visitor);
        return chatRuntime.start(ChatActor.visitor(visitor, agent.spaceId()), agent,
                request.sessionId(), request.message(), ChatService.SOURCE_PUBLIC);
    }

    @PostMapping("/requests/{requestId}/stop")
    public ApiResponse<ChatStopResult> stop(@PathVariable String requestId,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        String visitor = visitorTokenService.requireVisitor(request, response);
        checkRate(request, visitor);
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
        checkRate(httpRequest, visitor);
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
                clientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"), requestId);
        return ApiResponse.ok(request.approved());
    }

    /**
     * Visitor cookies can be reset at will, so every public call is charged to both the token and
     * the trusted client IP; otherwise the rate limit can be bypassed by clearing the cookie.
     */
    private void checkRate(HttpServletRequest request, String visitor) {
        int limit = properties.getPublicChat().getRateLimitPerMinute();
        rateLimiter.check("ip|" + clientIpResolver.resolve(request), limit);
        rateLimiter.check("visitor|" + visitor, limit);
    }
}
