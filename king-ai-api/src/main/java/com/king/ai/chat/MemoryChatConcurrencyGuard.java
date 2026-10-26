package com.king.ai.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process concurrency guard used for single-instance deployments and local development.
 * A slot is released by the chat runtime when the stream completes, fails or is cancelled.
 */
@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryChatConcurrencyGuard implements ChatConcurrencyGuard {

    private final Map<Long, Set<String>> active = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(Long spaceId, String requestId, int limit) {
        if (spaceId == null || requestId == null || limit <= 0) {
            return true;
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        active.compute(spaceId, (id, current) -> {
            Set<String> requests = current == null ? new HashSet<>() : current;
            if (requests.contains(requestId)) {
                acquired.set(true);
                return requests;
            }
            if (requests.size() >= limit) {
                return requests;
            }
            requests.add(requestId);
            acquired.set(true);
            return requests;
        });
        return acquired.get();
    }

    @Override
    public void release(Long spaceId, String requestId) {
        if (spaceId == null || requestId == null) {
            return;
        }
        active.computeIfPresent(spaceId, (id, requests) -> {
            requests.remove(requestId);
            return requests.isEmpty() ? null : requests;
        });
    }
}
