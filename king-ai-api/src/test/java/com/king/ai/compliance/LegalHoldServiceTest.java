package com.king.ai.compliance;

import com.king.ai.compliance.dto.LegalHoldCreateRequest;
import com.king.ai.compliance.retention.LegalHold;
import com.king.ai.compliance.retention.LegalHoldMapper;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalHoldServiceTest {

    private final LegalHoldMapper mapper = mock(LegalHoldMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LegalHoldService service = new LegalHoldService(mapper, jdbcTemplate,
            mock(com.king.ai.audit.AuditLogService.class));

    @Test
    void rejectsUntilInThePast() {
        LegalHoldCreateRequest request = new LegalHoldCreateRequest(
                "SESSION", 9L, "诉讼保全", LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.create(1L, request, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("截止时间");
    }

    @Test
    void rejectsUnknownResourceType() {
        LegalHoldCreateRequest request = new LegalHoldCreateRequest(
                "UNKNOWN", 9L, "原因", null);

        assertThatThrownBy(() -> service.create(1L, request, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保留类型");
    }

    @Test
    void releasingAlreadyReleasedHoldConflicts() {
        LegalHold hold = new LegalHold();
        hold.setId(3L);
        hold.setSpaceId(1L);
        hold.setReleasedAt(LocalDateTime.now());
        when(mapper.selectById(3L)).thenReturn(hold);

        assertThatThrownBy(() -> service.release(1L, 3L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已解除");
    }
}
