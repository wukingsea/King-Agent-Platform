package com.king.ai.chat;

/**
 * Tracks how many chat streams a single space is currently running so one tenant cannot
 * exhaust the shared chat executor (noisy-neighbor protection).
 */
public interface ChatConcurrencyGuard {

    boolean tryAcquire(Long spaceId, String requestId, int limit);

    void release(Long spaceId, String requestId);
}
