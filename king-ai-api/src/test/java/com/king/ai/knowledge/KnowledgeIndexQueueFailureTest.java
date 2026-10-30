package com.king.ai.knowledge;

import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeIndexJobMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIndexQueueFailureTest {

    @Test
    void failedReturnsDeadLetterJobs() {
        KnowledgeIndexJobMapper mapper = mock(KnowledgeIndexJobMapper.class);
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setDocId(3L);
        job.setStatus(KnowledgeIndexQueue.STATUS_FAILED);
        when(mapper.selectList(any())).thenReturn(List.of(job));
        KnowledgeIndexQueue queue = new KnowledgeIndexQueue(mapper,
                mock(ApplicationEventPublisher.class));

        List<KnowledgeIndexJob> failed = queue.failed(10);

        assertThat(failed).containsExactly(job);
    }
}
