package com.king.ai.common;

import java.time.Duration;

/**
 * Cross-instance lock for scheduled jobs. A job that fails to acquire the lock must skip
 * its run instead of duplicating destructive work on every replica.
 */
public interface JobLock {

    boolean tryAcquire(String name, String owner, Duration ttl);

    void release(String name, String owner);
}
