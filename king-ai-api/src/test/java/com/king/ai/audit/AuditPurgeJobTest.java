package com.king.ai.audit;

import com.king.ai.common.JobLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditPurgeJobTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final JobLock jobLock = mock(JobLock.class);
    private final AuditPurgeJob job = new AuditPurgeJob(auditLogService, jobLock);

    @Test
    void skipsWhenAnotherInstanceHoldsTheLock() {
        when(jobLock.tryAcquire(eq("audit-retention"), anyString(), any(Duration.class)))
                .thenReturn(false);

        job.run();

        verify(auditLogService, never()).purgeExpired();
        verify(jobLock, never()).release(anyString(), anyString());
    }

    @Test
    void purgesUnderTheDistributedLockAndReleasesIt() {
        when(jobLock.tryAcquire(eq("audit-retention"), anyString(), any(Duration.class)))
                .thenReturn(true);

        job.run();

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(jobLock).tryAcquire(eq("audit-retention"), owner.capture(), any(Duration.class));
        verify(auditLogService).purgeExpired();
        verify(jobLock).release("audit-retention", owner.getValue());
        assertThat(owner.getValue()).isNotBlank();
    }
}
