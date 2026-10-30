package com.king.ai.knowledge;

import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.common.BizException;
import com.king.ai.knowledge.dto.KnowledgeDocView;
import com.king.ai.knowledge.dto.KnowledgeIndexJobView;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.entity.KnowledgeIndexJob;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.knowledge.ocr.OcrService;
import com.king.ai.knowledge.vector.VectorStore;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexReplayTest {

    private KnowledgeBaseMapper baseMapper;
    private KnowledgeDocMapper docMapper;
    private KnowledgeIndexQueue indexQueue;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        baseMapper = mock(KnowledgeBaseMapper.class);
        docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        indexQueue = mock(KnowledgeIndexQueue.class);
        service = new KnowledgeService(baseMapper, docMapper, chunkMapper,
                mock(SupplierModelMapper.class), mock(AgentKnowledgeMapper.class),
                mock(VectorStore.class), mock(EmbeddingService.class),
                mock(KnowledgeTextSplitter.class), mock(ModelRuntimeService.class), indexQueue,
                mock(JdbcTemplate.class), mock(OcrService.class));
    }

    @Test
    void replayResetsAFailedJobWithoutRebuildingTheDocument() {
        when(docMapper.selectById(3L)).thenReturn(doc(3L, 2L, "FAILED"));
        when(indexQueue.find(3L)).thenReturn(job(3L, KnowledgeIndexQueue.STATUS_FAILED));

        KnowledgeDocView view = service.replayFailedIndex(1L, 2L, 3L);

        assertThat(view.status()).isEqualTo(KnowledgeService.STATUS_PENDING);
        verify(indexQueue).enqueue(3L);
        verify(docMapper).updateById(any(KnowledgeDoc.class));
    }

    @Test
    void replayRejectsJobsThatAreNotFailed() {
        when(docMapper.selectById(3L)).thenReturn(doc(3L, 2L, "PENDING"));
        when(indexQueue.find(3L)).thenReturn(job(3L, KnowledgeIndexQueue.STATUS_RUNNING));

        assertThatThrownBy(() -> service.replayFailedIndex(1L, 2L, 3L))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        verify(indexQueue, never()).enqueue(3L);
    }

    @Test
    void failedJobsAreScopedToTheRequestedKnowledgeBase() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(2L);
        base.setSpaceId(1L);
        when(baseMapper.selectById(2L)).thenReturn(base);
        when(indexQueue.failed(50)).thenReturn(List.of(
                job(3L, KnowledgeIndexQueue.STATUS_FAILED),
                job(4L, KnowledgeIndexQueue.STATUS_FAILED)));
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(
                doc(3L, 2L, "FAILED"), doc(4L, 9L, "FAILED")));

        List<KnowledgeIndexJobView> jobs = service.failedIndexJobs(1L, 2L, 50);

        assertThat(jobs).extracting(KnowledgeIndexJobView::docId).containsExactly(3L);
        assertThat(jobs.get(0).docName()).isEqualTo("doc-3");
    }

    private KnowledgeDoc doc(Long id, Long knowledgeBaseId, String status) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setSpaceId(1L);
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setName("doc-" + id);
        doc.setStatus(status);
        doc.setContent("content");
        return doc;
    }

    private KnowledgeIndexJob job(Long docId, String status) {
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setDocId(docId);
        job.setStatus(status);
        job.setAttempts(5);
        job.setLastError("boom");
        return job;
    }
}
