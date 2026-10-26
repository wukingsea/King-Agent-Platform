package com.king.ai.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryChatConcurrencyGuardTest {

    private final MemoryChatConcurrencyGuard guard = new MemoryChatConcurrencyGuard();

    @Test
    void rejectsRequestsBeyondTheSpaceLimit() {
        assertThat(guard.tryAcquire(1L, "req-1", 2)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-2", 2)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-3", 2)).isFalse();
    }

    @Test
    void releaseFreesTheSlotForAnotherRequest() {
        guard.tryAcquire(1L, "req-1", 1);
        assertThat(guard.tryAcquire(1L, "req-2", 1)).isFalse();

        guard.release(1L, "req-1");

        assertThat(guard.tryAcquire(1L, "req-2", 1)).isTrue();
    }

    @Test
    void repeatedAcquireOfTheSameRequestIsIdempotent() {
        assertThat(guard.tryAcquire(1L, "req-1", 1)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-1", 1)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-2", 1)).isFalse();
    }

    @Test
    void spacesAreIsolatedFromEachOther() {
        assertThat(guard.tryAcquire(1L, "req-1", 1)).isTrue();
        assertThat(guard.tryAcquire(2L, "req-2", 1)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-3", 1)).isFalse();
    }

    @Test
    void nonPositiveLimitDisablesTheQuota() {
        assertThat(guard.tryAcquire(1L, "req-1", 0)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-2", 0)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-3", -1)).isTrue();
    }

    @Test
    void missingIdentifiersDoNotConsumeSlots() {
        assertThat(guard.tryAcquire(null, "req-1", 1)).isTrue();
        assertThat(guard.tryAcquire(1L, null, 1)).isTrue();
        assertThat(guard.tryAcquire(1L, "req-2", 1)).isTrue();
    }
}
