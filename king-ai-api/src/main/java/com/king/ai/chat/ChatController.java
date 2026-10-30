package com.king.ai.chat;

import com.king.ai.agent.AgentRuntime;
import com.king.ai.agent.AgentService;
import com.king.ai.audit.AuditLogService;
import com.king.ai.chat.dto.ChatMessageView;
import com.king.ai.chat.dto.ChatSendRequest;
import com.king.ai.chat.dto.ChatSessionRenameRequest;
import com.king.ai.chat.dto.ChatSessionView;
import com.king.ai.chat.dto.ChatStopResult;
import com.king.ai.chat.dto.ToolApprovalRequest;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.BizException;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.system.SpaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatRuntime chatRuntime;
    private final AgentService agentService;
    private final ChatCancellationRegistry cancellationRegistry;
    private final ToolApprovalRegistry toolApprovalRegistry;
    private final SpaceService spaceService;
    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/sessions")
    public ApiResponse<PageResult<ChatSessionView>> sessions(@RequestParam(required = false) Long agentId,
                                                             @RequestParam(required = false) String sourceType,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(defaultValue = "1") Integer page,
                                                             @RequestParam(defaultValue = "20") Integer size) {
        return ApiResponse.ok(chatService.listSessions(RequestContext.require(), agentId, sourceType,
                keyword, page, size));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageView>> messages(@PathVariable Long sessionId) {
        return ApiResponse.ok(chatService.messages(RequestContext.require(), sessionId));
    }

    @PutMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionView> rename(@PathVariable Long sessionId,
                                               @Valid @RequestBody ChatSessionRenameRequest request) {
        return ApiResponse.ok(chatService.rename(RequestContext.require(), sessionId, request.title()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(RequestContext.require(), sessionId);
        return ApiResponse.ok();
    }

    @PostMapping("/send")
    public SseEmitter send(@Valid @RequestBody ChatSendRequest request) {
        var principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        AgentRuntime agent = agentService.liveRuntime(agentService.require(spaceId, request.agentId()));
        ChatActor actor = ChatActor.user(principal.id(), principal.visitorId(), principal.username(),
                spaceId, principal.isAdmin() ? "OWNER" : principal.spaceRole(),
                spaceService.canManageSessions(principal, spaceId));
        return chatRuntime.start(actor, agent, request.sessionId(), request.message(),
                ChatService.SOURCE_ADMIN);
    }

    @PostMapping("/requests/{requestId}/stop")
    public ApiResponse<ChatStopResult> stop(@PathVariable String requestId) {
        var principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        boolean canManage = spaceService.canManageSessions(principal, spaceId);
        return ApiResponse.ok(new ChatStopResult(
                cancellationRegistry.cancel(requestId, spaceId, principal.visitorId(), canManage)));
    }

    @PostMapping("/requests/{requestId}/tool-approvals/{callId}")
    public ApiResponse<Boolean> approveTool(@PathVariable String requestId,
                                            @PathVariable String callId,
                                            @Valid @RequestBody ToolApprovalRequest request,
                                            HttpServletRequest httpRequest) {
        var principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        boolean canManage = spaceService.canManageSessions(principal, spaceId);
        String actorKey = ChatActor.user(principal.id(), principal.visitorId(), principal.username(),
                spaceId, principal.isAdmin() ? "OWNER" : principal.spaceRole(), canManage).key();
        ToolApprovalRegistry.Pending pending = toolApprovalRegistry.decide(requestId, callId, spaceId,
                actorKey, canManage, request.approved());
        if (pending == null) {
            throw BizException.notFound("待确认的工具调用不存在或已过期");
        }
        auditLogService.recordChat(spaceId, principal.id(), principal.username(), "chat.tool_approval",
                requestId + ":" + callId,
                request.approved() ? AuditLogService.STATUS_SUCCESS : AuditLogService.STATUS_DENIED,
                (request.approved() ? "批准" : "拒绝") + "工具调用 " + pending.toolName()
                        + "（来源：" + pending.source() + "）",
                clientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"), requestId);
        return ApiResponse.ok(request.approved());
    }
}
