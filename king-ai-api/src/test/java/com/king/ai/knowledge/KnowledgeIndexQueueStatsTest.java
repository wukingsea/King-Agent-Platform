package com.king.ai.knowledge;

import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeIndexJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexQueueStatsTest {

    @Mock
    private KnowledgeIndexJobMapper mapper;

    @Mock
    private ApplicationEventPublisher publisher;

    @Test
    void returnsPendingFailedAndOldestNextRun() {
        LocalDateTime oldest = LocalDateTime.now().minusMinutes(5);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setNextRunAt(oldest);
        when(mapper.selectCount(any())).thenReturn(3L, 1L);
        when(mapper.selectOne(any())).thenReturn(job);
        KnowledgeIndexQueue queue = new KnowledgeIndexQueue(mapper, publisher);

        QueueStats stats = queue.stats();

        assertThat(stats.pending()).isEqualTo(3L);
        assertThat(stats.failed()).isEqualTo(1L);
        assertThat(stats.oldestPendingAt()).isEqualTo(oldest);
    }
}
