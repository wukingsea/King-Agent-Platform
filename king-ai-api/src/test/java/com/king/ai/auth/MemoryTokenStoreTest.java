package com.king.ai.auth;

import com.king.ai.auth.dto.LoginSessionView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryTokenStoreTest {

    @Test
    void listsUserSessionsAndMarksTheCurrentOne() {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save("token-a", 1L, Duration.ofMinutes(30), "10.0.0.1", "Chrome");
        store.save("token-b", 1L, Duration.ofMinutes(30), "10.0.0.2", "Firefox");
        store.save("token-c", 2L, Duration.ofMinutes(30), "10.0.0.3", "Safari");

        List<LoginSessionView> sessions = store.sessions(1L, "token-a");

        assertThat(sessions).hasSize(2);
        assertThat(sessions).filteredOn(LoginSessionView::current).singleElement()
                .extracting(LoginSessionView::id)
                .isEqualTo(TokenStore.sessionId("token-a"));
        assertThat(sessions).extracting(LoginSessionView::clientIp)
                .containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");
    }

    @Test
    void deleteOthersKeepsCurrentSessionOnly() {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save("token-a", 1L, Duration.ofMinutes(30), "10.0.0.1", "Chrome");
        store.save("token-b", 1L, Duration.ofMinutes(30), "10.0.0.2", "Firefox");
        store.save("token-c", 2L, Duration.ofMinutes(30), "10.0.0.3", "Safari");

        int revoked = store.deleteOthers(1L, "token-a");

        assertThat(revoked).isEqualTo(1);
        assertThat(store.findUserId("token-a")).isEqualTo(1L);
        assertThat(store.findUserId("token-b")).isNull();
        assertThat(store.findUserId("token-c")).isEqualTo(2L);
    }

    @Test
    void deleteByUserIdRevokesEverySessionOfTheUser() {
        MemoryTokenStore store = new MemoryTokenStore();
        store.save("token-a", 1L, Duration.ofMinutes(30), "10.0.0.1", "Chrome");
        store.save("token-b", 1L, Duration.ofMinutes(30), "10.0.0.2", "Firefox");
        store.save("token-c", 2L, Duration.ofMinutes(30), "10.0.0.3", "Safari");

        store.deleteByUserId(1L);

        assertThat(store.sessions(1L, null)).isEmpty();
        assertThat(store.findUserId("token-c")).isEqualTo(2L);
    }
}
