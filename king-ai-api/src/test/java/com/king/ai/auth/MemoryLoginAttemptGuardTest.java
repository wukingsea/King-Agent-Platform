package com.king.ai.auth;

import com.king.ai.common.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryLoginAttemptGuardTest {

    private final MemoryLoginAttemptGuard guard = new MemoryLoginAttemptGuard();

    @Test
    void fifthFailureLocksTheUsernameAndIpPair() {
        for (int i = 0; i < LoginFailurePolicy.PAIR_THRESHOLD - 1; i++) {
            guard.fail("alice", "203.0.113.7");
        }
        assertThatCode(() -> guard.check("alice", "203.0.113.7")).doesNotThrowAnyException();

        guard.fail("alice", "203.0.113.7");

        assertThatThrownBy(() -> guard.check("alice", "203.0.113.7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("5 分钟");
    }

    @Test
    void lockIsScopedToTheUsernameAndIpPair() {
        for (int i = 0; i < LoginFailurePolicy.PAIR_THRESHOLD; i++) {
            guard.fail("alice", "203.0.113.7");
        }

        assertThatCode(() -> guard.check("alice", "198.51.100.9")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("bob", "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsThePairCounter() {
        for (int i = 0; i < LoginFailurePolicy.PAIR_THRESHOLD - 1; i++) {
            guard.fail("alice", "203.0.113.7");
        }

        guard.success("alice", "203.0.113.7");
        guard.fail("alice", "203.0.113.7");

        assertThatCode(() -> guard.check("alice", "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void distributedFailuresLockTheAccountEvenFromNewIps() {
        for (int i = 0; i < LoginFailurePolicy.ACCOUNT_THRESHOLD; i++) {
            guard.fail("alice", "10.0." + (i / 250) + "." + (i % 250));
        }

        assertThatThrownBy(() -> guard.check("alice", "192.0.2.55"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("账号");
    }

    @Test
    void oneIpHammeringManyAccountsLocksTheIp() {
        for (int i = 0; i < LoginFailurePolicy.IP_THRESHOLD; i++) {
            guard.fail("user" + i, "198.51.100.23");
        }

        assertThatThrownBy(() -> guard.check("another-user", "198.51.100.23"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IP");
    }

    @Test
    void lockDurationGrowsWithRepeatedFailures() {
        assertThat(LoginFailurePolicy.lockDuration(LoginFailurePolicy.PAIR_THRESHOLD).toMinutes())
                .isEqualTo(5);
        assertThat(LoginFailurePolicy.lockDuration(10).toMinutes()).isEqualTo(30);
        assertThat(LoginFailurePolicy.lockDuration(15).toMinutes()).isEqualTo(120);
        assertThat(LoginFailurePolicy.lockDuration(4)).isZero();
    }
}
