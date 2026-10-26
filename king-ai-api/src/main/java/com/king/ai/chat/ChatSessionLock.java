package com.king.ai.chat;

/**
 * Serializes message generation per chat session so two concurrent requests cannot
 * interleave user/assistant messages or overwrite each other's progress.
 */
public interface ChatSessionLock {

    boolean tryAcquire(Long sessionId, String owner);

    void release(Long sessionId, String owner);
}
