package com.king.ai.common;

import org.slf4j.Logger;

/**
 * Redis failure semantics shared by every Redis-backed guard.
 *
 * <p>Two policies exist and must be chosen explicitly per operation:
 * <ul>
 *   <li><b>Fail closed</b> for enforcement points (login throttling, public abuse limits,
 *       per-session serialization). If Redis is unavailable the guard cannot prove the request is
 *       safe, so the request is rejected with a retryable 503 instead of silently bypassing the
 *       protection.</li>
 *   <li><b>Fail open</b> for best-effort bookkeeping and cleanup (failure counters, lock release,
 *       cancellation metadata). These must never turn a working business flow into an error.</li>
 * </ul>
 *
 * <p>Fail-closed errors are {@link BizException}s with HTTP 503 so the API contract is stable and
 * clients do not see a generic internal error.
 */
public final class RedisFailurePolicy {

    private static final int UNAVAILABLE = 503;
    private static final String UNAVAILABLE_MESSAGE = "服务暂时不可用，请稍后重试";

    private RedisFailurePolicy() {
    }

    public static BizException failClosed(String operation, RuntimeException cause) {
        return new BizException(UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }

    public static void failOpen(Logger log, String operation, RuntimeException cause) {
        log.warn("Redis unavailable during {}, continuing without it: {}", operation,
                cause.getMessage());
    }
}
