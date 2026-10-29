package com.king.ai.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-replica approval registry. Safe only when chat requests cannot move between instances
 * ({@code king-ai.token-store=memory}); otherwise approvals are decided on a replica that does
 * not own the stream and never reach the waiting tool call.
 */
@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryToolApprovalRegistry implements ToolApprovalRegistry {

    private final Map<String, Map<String, LocalCall>> pending = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Boolean> register(String requestId, String callId, Long spaceId,
                                               String actorKey, String toolName, String source,
                                               String arguments) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        pending.computeIfAbsent(requestId, key -> new ConcurrentHashMap<>())
                .put(callId, new LocalCall(spaceId, actorKey, toolName, source, arguments, decision));
        return decision;
    }

    @Override
    public Pending decide(String requestId, String callId, Long spaceId, String actorKey,
                          boolean canManage, boolean approved) {
        Map<String, LocalCall> calls = pending.get(requestId);
        if (calls == null) {
            return null;
        }
        LocalCall call = calls.get(callId);
        if (call == null || !allowed(call, spaceId, actorKey, canManage)) {
            return null;
        }
        return call.decision().complete(approved) ? call.metadata() : null;
    }

    @Override
    public void remove(String requestId, String callId) {
        Map<String, LocalCall> calls = pending.get(requestId);
        if (calls == null) {
            return;
        }
        calls.remove(callId);
        if (calls.isEmpty()) {
            pending.remove(requestId);
        }
    }

    @Override
    public void removeRequest(String requestId) {
        if (requestId != null) {
            pending.remove(requestId);
        }
    }

    private static boolean allowed(LocalCall call, Long spaceId, String actorKey, boolean canManage) {
        if (spaceId != null && !Objects.equals(call.spaceId(), spaceId)) {
            return false;
        }
        return canManage || Objects.equals(call.actorKey(), actorKey);
    }

    private record LocalCall(Long spaceId, String actorKey, String toolName, String source,
                             String arguments, CompletableFuture<Boolean> decision) {

        ToolApprovalRegistry.Pending metadata() {
            return new ToolApprovalRegistry.Pending(spaceId, actorKey, toolName, source, arguments);
        }
    }
}
