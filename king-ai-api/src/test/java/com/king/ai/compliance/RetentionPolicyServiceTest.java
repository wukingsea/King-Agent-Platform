package com.king.ai.compliance;

import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.compliance.dto.RetentionPolicyUpdateRequest;
import com.king.ai.compliance.retention.RetentionPolicyMapper;
import com.king.ai.compliance.retention.RetentionPolicyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RetentionPolicyServiceTest {

    private final RetentionPolicyService service = new RetentionPolicyService(
            mock(RetentionPolicyMapper.class), mock(AuditLogService.class));

    @Test
    void rejectsArchiveForMessagePolicy() {
        assertThatThrownBy(() -> service.upsert(1L,
                new RetentionPolicyUpdateRequest("MESSAGE", 30, true, true), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归档");
    }

    @Test
    void rejectsArchiveForAuditPolicy() {
        assertThatThrownBy(() -> service.upsert(1L,
                new RetentionPolicyUpdateRequest("AUDIT", 30, true, true), 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归档");
    }
}
