package com.king.ai.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending human-in-the-loop approvals for tool calls. Approval is scoped to the chat request
 * and to the actor who started it (space managers may approve on behalf of others).
 */
@Component
public class ToolApprovalRegistry {

    private final Map<String, Map<String, Pending>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Boolean> register(String requestId, String callId, Long spaceId,
                                               String actorKey, String toolName, String source,
                                               String arguments) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        pending.computeIfAbsent(requestId, key -> new ConcurrentHashMap<>())
                .put(callId, new Pending(spaceId, actorKey, toolName, source, arguments, decision));
        return decision;
    }

    /**
     * Resolves a pending approval. Returns the pending call on success so callers can audit
     * which tool was approved; returns {@code null} when the call is unknown, expired or the
     * actor is not allowed to decide for it.
     */
    public Pending decide(String requestId, String callId, Long spaceId, String actorKey,
                          boolean canManage, boolean approved) {
        Map<String, Pending> calls = pending.get(requestId);
        if (calls == null) {
            return null;
        }
        Pending call = calls.get(callId);
        if (call == null) {
            return null;
        }
        if (spaceId != null && !Objects.equals(call.spaceId(), spaceId)) {
            return null;
        }
        if (!canManage && !Objects.equals(call.actorKey(), actorKey)) {
            return null;
        }
        return call.decision().complete(approved) ? call : null;
    }

    public void remove(String requestId, String callId) {
        Map<String, Pending> calls = pending.get(requestId);
        if (calls == null) {
            return;
        }
        calls.remove(callId);
        if (calls.isEmpty()) {
            pending.remove(requestId);
        }
    }

    public void removeRequest(String requestId) {
        if (requestId != null) {
            pending.remove(requestId);
        }
    }

    public record Pending(Long spaceId, String actorKey, String toolName, String source,
                          String arguments, CompletableFuture<Boolean> decision) {
    }
}
