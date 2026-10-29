package com.king.ai.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryJobLockTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final MemoryJobLock lock = new MemoryJobLock(new MutableClock(now));

    @Test
    void secondOwnerCannotAcquireUntilFirstReleases() {
        assertThat(lock.tryAcquire("retention", "owner-a", Duration.ofMinutes(5))).isTrue();

        assertThat(lock.tryAcquire("retention", "owner-b", Duration.ofMinutes(5))).isFalse();

        lock.release("retention", "owner-a");
        assertThat(lock.tryAcquire("retention", "owner-b", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void anotherOwnerCannotReleaseTheLock() {
        lock.tryAcquire("retention", "owner-a", Duration.ofMinutes(5));

        lock.release("retention", "owner-b");

        assertThat(lock.tryAcquire("retention", "owner-b", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void expiredLockCanBeAcquired() {
        lock.tryAcquire("retention", "owner-a", Duration.ofMinutes(5));
        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);

        assertThat(lock.tryAcquire("retention", "owner-b", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void locksWithDifferentNamesDoNotConflict() {
        assertThat(lock.tryAcquire("retention", "owner-a", Duration.ofMinutes(5))).isTrue();
        assertThat(lock.tryAcquire("access-review", "owner-b", Duration.ofMinutes(5))).isTrue();
    }

    private static final class MutableClock extends Clock {

        private final AtomicLong current;

        private MutableClock(AtomicLong current) {
            this.current = current;
        }

        @Override
        public long millis() {
            return current.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(current.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
