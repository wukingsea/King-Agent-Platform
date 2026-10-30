package com.king.ai.knowledge.vector;

import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.ChunkVector;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.mapper.ChunkVectorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalVectorStoreTest {

    @Mock
    private ChunkVectorMapper chunkVectorMapper;

    private KingAiProperties properties;
    private LocalVectorStore store;

    @BeforeEach
    void setUp() {
        properties = new KingAiProperties();
        properties.getLocalVector().setScanBatchSize(2);
        properties.getLocalVector().setMaxScan(0);
        store = new LocalVectorStore(chunkVectorMapper, properties);
    }

    @Test
    void keepsBestHitsWhilePagingThroughEveryChunk() {
        when(chunkVectorMapper.scanBatch(9L, 0L, 2)).thenReturn(List.of(
                vector(1L, "1.0,0.0"), vector(2L, "0.9,0.1")));
        when(chunkVectorMapper.scanBatch(9L, 2L, 2)).thenReturn(List.of(
                vector(3L, "-1.0,0.0"), vector(4L, "0.5,0.5")));
        when(chunkVectorMapper.scanBatch(9L, 4L, 2)).thenReturn(List.of(
                vector(5L, "0.1,0.9")));

        List<VectorHit> hits = store.search(base(9L), new float[]{1f, 0f}, 2);

        assertThat(hits).extracting(VectorHit::chunkId).containsExactly(1L, 2L);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void skipsVectorsWithDifferentDimensionOrMalformedEmbedding() {
        when(chunkVectorMapper.scanBatch(9L, 0L, 2)).thenReturn(List.of(
                vector(1L, "1.0,0.0"), vector(2L, "1.0")));
        when(chunkVectorMapper.scanBatch(9L, 2L, 2)).thenReturn(List.of(
                vector(3L, "not-a-vector")));

        List<VectorHit> hits = store.search(base(9L), new float[]{1f, 0f}, 5);

        assertThat(hits).extracting(VectorHit::chunkId).containsExactly(1L);
    }

    @Test
    void stopsAtTheConfiguredScanCap() {
        properties.getLocalVector().setMaxScan(2);
        when(chunkVectorMapper.scanBatch(9L, 0L, 2)).thenReturn(List.of(
                vector(1L, "1.0,0.0"), vector(2L, "0.0,1.0")));

        List<VectorHit> hits = store.search(base(9L), new float[]{1f, 0f}, 5);

        assertThat(hits).extracting(VectorHit::chunkId).containsExactly(1L, 2L);
        verify(chunkVectorMapper, never()).scanBatch(9L, 2L, 2);
    }

    @Test
    void returnsEmptyWithoutQueryOrTopK() {
        assertThat(store.search(base(9L), new float[0], 5)).isEmpty();
        assertThat(store.search(base(9L), new float[]{1f}, 0)).isEmpty();
        verify(chunkVectorMapper, never()).scanBatch(9L, 0L, 2);
    }

    private static KnowledgeBase base(Long knowledgeBaseId) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(knowledgeBaseId);
        base.setSpaceId(1L);
        return base;
    }

    private static ChunkVector vector(Long chunkId, String embedding) {
        ChunkVector vector = new ChunkVector();
        vector.setChunkId(chunkId);
        vector.setEmbedding(embedding);
        return vector;
    }
}
