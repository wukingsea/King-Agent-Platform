package com.king.ai.auth;

import com.king.ai.common.BizException;
import com.king.ai.system.entity.PasswordHistory;
import com.king.ai.system.mapper.PasswordHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordHistoryServiceTest {

    private PasswordHistoryMapper historyMapper;
    private PasswordEncoder passwordEncoder;
    private PasswordHistoryService service;

    @BeforeEach
    void setUp() {
        historyMapper = mock(PasswordHistoryMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new PasswordHistoryService(historyMapper, passwordEncoder);
    }

    @Test
    void rejectsPasswordMatchingARecentHash() {
        when(historyMapper.selectList(any())).thenReturn(List.of(entry(1L, "old-hash")));
        when(passwordEncoder.matches("KingAi2026", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.ensureNotReused(7L, "KingAi2026"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最近 5 次");
    }

    @Test
    void acceptsPasswordNotInHistory() {
        when(historyMapper.selectList(any())).thenReturn(List.of(entry(1L, "old-hash")));
        when(passwordEncoder.matches("KingAi2026", "old-hash")).thenReturn(false);

        assertThatCode(() -> service.ensureNotReused(7L, "KingAi2026")).doesNotThrowAnyException();
    }

    @Test
    void recordInsertsHashAndTrimsEntriesBeyondLimit() {
        when(historyMapper.selectList(any())).thenReturn(List.of(
                entry(5L, "h5"), entry(4L, "h4"), entry(3L, "h3"), entry(2L, "h2"), entry(1L, "h1")));

        service.record(7L, "new-hash");

        verify(historyMapper).insert(any(PasswordHistory.class));
        verify(historyMapper).delete(any());
    }

    private static PasswordHistory entry(Long id, String hash) {
        PasswordHistory entry = new PasswordHistory();
        entry.setId(id);
        entry.setUserId(7L);
        entry.setPasswordHash(hash);
        return entry;
    }
}
