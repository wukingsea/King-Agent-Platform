package com.king.ai.chat;

import java.util.concurrent.CompletableFuture;

/**
 * Pending human-in-the-loop approvals for tool calls. Approval is scoped to the chat request
 * and to the actor who started it (space managers may approve on behalf of others).
 */
public interface ToolApprovalRegistry {

    CompletableFuture<Boolean> register(String requestId, String callId, Long spaceId,
                                        String actorKey, String toolName, String source,
                                        String arguments);

    /**
     * Resolves a pending approval. Returns the pending call on success so callers can audit
     * which tool was approved; returns {@code null} when the call is unknown, expired, already
     * decided or the actor is not allowed to decide for it.
     */
    Pending decide(String requestId, String callId, Long spaceId, String actorKey,
                   boolean canManage, boolean approved);

    void remove(String requestId, String callId);

    void removeRequest(String requestId);

    record Pending(Long spaceId, String actorKey, String toolName, String source, String arguments) {
    }
}
