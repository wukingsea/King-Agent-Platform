package com.king.ai.knowledge;

import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.common.BizException;
import com.king.ai.knowledge.dto.RetrievedChunk;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeChunk;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.knowledge.ocr.OcrService;
import com.king.ai.knowledge.rerank.RerankClient;
import com.king.ai.knowledge.vector.VectorHit;
import com.king.ai.knowledge.vector.VectorStore;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖混合检索（dense + lexical）、文档级 ACL 下推与可选重排的集成行为。
 */
class HybridRetrievalTest {

    private KnowledgeBaseMapper baseMapper;
    private KnowledgeDocMapper docMapper;
    private KnowledgeChunkMapper chunkMapper;
    private VectorStore vectorStore;
    private EmbeddingService embeddingService;
    private RerankClient rerankClient;
    private ObjectProvider<RerankClient> rerankProvider;
    private KnowledgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        baseMapper = mock(KnowledgeBaseMapper.class);
        docMapper = mock(KnowledgeDocMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        vectorStore = mock(VectorStore.class);
        embeddingService = mock(EmbeddingService.class);
        rerankClient = mock(RerankClient.class);
        rerankProvider = mock(ObjectProvider.class);
        when(rerankProvider.getIfAvailable()).thenReturn(rerankClient);
        when(rerankClient.rerank(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new KnowledgeService(baseMapper, docMapper, chunkMapper,
                mock(SupplierModelMapper.class), mock(AgentKnowledgeMapper.class),
                vectorStore, embeddingService, mock(KnowledgeTextSplitter.class),
                mock(ModelRuntimeService.class), mock(KnowledgeIndexQueue.class),
                mock(JdbcTemplate.class), mock(OcrService.class), rerankProvider);
    }

    @Test
    void vectorAndLexicalCandidatesAreMergedAndBoosted() {
        KnowledgeBase base = base(9L);
        KnowledgeChunk vectorChunk = chunk(1L, 10L, 0, "向量命中的分块内容");
        KnowledgeChunk lexicalChunk = chunk(2L, 11L, 1, "refund policy 退款流程说明");
        KnowledgeDoc doc10 = doc(10L, null);
        KnowledgeDoc doc11 = doc(11L, null);

        when(embeddingService.embedQuery(base, "refund policy")).thenReturn(new float[]{0.1f});
        when(vectorStore.search(eq(base), any(), eq(4), anySet()))
                .thenReturn(List.of(new VectorHit(1L, 0.80)));
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(vectorChunk));
        when(chunkMapper.selectList(any())).thenReturn(List.of(vectorChunk, lexicalChunk));
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(doc10, doc11));

        List<RetrievedChunk> result = service.retrieve(base, "refund policy", 4, "VIEWER");

        assertThat(result).hasSize(2);
        RetrievedChunk boosted = result.stream()
                .filter(c -> c.chunkId().equals(1L)).findFirst().orElseThrow();
        RetrievedChunk lexicalOnly = result.stream()
                .filter(c -> c.chunkId().equals(2L)).findFirst().orElseThrow();
        // 同时命中 vector+lexical 的候选获得 +0.08+0.04 加成，纯 lexical 候选 0.35+0.04
        assertThat(boosted.score()).isCloseTo(0.92, within(0.0001));
        assertThat(lexicalOnly.score()).isCloseTo(0.39, within(0.0001));
    }

    @Test
    void restrictedDocsAreExcludedFromVectorCandidatesAndResults() {
        KnowledgeBase base = base(9L);
        KnowledgeChunk chunk = chunk(5L, 7L, 0, "仅管理员可见的内容");
        KnowledgeDoc restricted = doc(7L, "ADMIN,OWNER");

        when(embeddingService.embedQuery(base, "q")).thenReturn(new float[]{0.1f});
        when(docMapper.selectList(any())).thenReturn(List.of(restricted));
        when(vectorStore.search(eq(base), any(), eq(4), anySet()))
                .thenReturn(List.of(new VectorHit(5L, 0.9)));
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(chunk));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(restricted));

        List<RetrievedChunk> result = service.retrieve(base, "q", 4, "VIEWER");

        assertThat(result).isEmpty();
        ArgumentCaptor<Set<Long>> denied = ArgumentCaptor.forClass(Set.class);
        verify(vectorStore).search(eq(base), any(), eq(4), denied.capture());
        assertThat(denied.getValue()).containsExactly(7L);
    }

    @Test
    void adminRoleCanReadRestrictedDocs() {
        KnowledgeBase base = base(9L);
        KnowledgeChunk chunk = chunk(5L, 7L, 0, "仅管理员可见的内容");
        KnowledgeDoc restricted = doc(7L, "ADMIN,OWNER");

        when(embeddingService.embedQuery(base, "q")).thenReturn(new float[]{0.1f});
        when(docMapper.selectList(any())).thenReturn(List.of(restricted));
        when(vectorStore.search(eq(base), any(), eq(4), anySet()))
                .thenReturn(List.of(new VectorHit(5L, 0.9)));
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(chunk));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(restricted));

        List<RetrievedChunk> result = service.retrieve(base, "q", 4, "ADMIN");

        assertThat(result).hasSize(1);
        ArgumentCaptor<Set<Long>> denied = ArgumentCaptor.forClass(Set.class);
        verify(vectorStore).search(eq(base), any(), eq(4), denied.capture());
        assertThat(denied.getValue()).isEmpty();
    }

    @Test
    void rerankClientReordersCandidatesWhenAvailable() {
        KnowledgeBase base = base(9L);
        KnowledgeChunk c1 = chunk(1L, 10L, 0, "first");
        KnowledgeChunk c2 = chunk(2L, 11L, 0, "second");
        when(embeddingService.embedQuery(base, "q")).thenReturn(new float[]{0.1f});
        when(vectorStore.search(eq(base), any(), eq(4), anySet()))
                .thenReturn(List.of(new VectorHit(1L, 0.9), new VectorHit(2L, 0.8)));
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c1, c2));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, null), doc(11L, null)));
        when(rerankClient.rerank(any(), any())).thenAnswer(inv -> {
            List<RetrievedChunk> in = inv.getArgument(1);
            return List.of(in.get(1), in.get(0));
        });

        List<RetrievedChunk> result = service.retrieve(base, "q", 4, "VIEWER");

        assertThat(result).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    void sameEmbeddingModelSharesOneQueryEmbeddingAcrossBases() {
        KnowledgeBase baseA = base(9L);
        baseA.setEmbeddingModelId(3L);
        KnowledgeBase baseB = base(10L);
        baseB.setEmbeddingModelId(3L);
        when(baseMapper.selectBatchIds(any())).thenReturn(List.of(baseA, baseB));
        when(embeddingService.embedQuery(any(KnowledgeBase.class), eq("q")))
                .thenReturn(new float[]{0.1f});
        when(vectorStore.search(any(), any(), anyInt(), anySet())).thenReturn(List.of());
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        List<RetrievedChunk> result = service.retrieveForAgent(1L, List.of(9L, 10L), "q", 8000, 0.1, "VIEWER");

        assertThat(result).isEmpty();
        // 两个 base 同属 embeddingModelId=3，只嵌入一次
        verify(embeddingService, org.mockito.Mockito.times(1))
                .embedQuery(any(KnowledgeBase.class), eq("q"));
        verify(vectorStore, org.mockito.Mockito.times(2))
                .search(any(), any(), anyInt(), anySet());
    }

    @Test
    void rerankFailureKeepsFusedOrder() {
        KnowledgeBase base = base(9L);
        KnowledgeChunk c1 = chunk(1L, 10L, 0, "first");
        KnowledgeChunk c2 = chunk(2L, 11L, 0, "second");
        when(embeddingService.embedQuery(base, "q")).thenReturn(new float[]{0.1f});
        when(vectorStore.search(eq(base), any(), eq(4), anySet()))
                .thenReturn(List.of(new VectorHit(1L, 0.9), new VectorHit(2L, 0.8)));
        when(chunkMapper.selectBatchIds(any())).thenReturn(List.of(c1, c2));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(doc(10L, null), doc(11L, null)));
        when(rerankClient.rerank(any(), any())).thenThrow(new RuntimeException("rerank down"));

        List<RetrievedChunk> result = service.retrieve(base, "q", 4, "VIEWER");

        assertThat(result).extracting(RetrievedChunk::chunkId).containsExactly(1L, 2L);
    }

    private static KnowledgeBase base(Long id) {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(id);
        base.setSpaceId(1L);
        base.setName("kb-" + id);
        base.setStatus(0);
        base.setTopK(4);
        return base;
    }

    private static KnowledgeChunk chunk(Long id, Long docId, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(9L);
        chunk.setDocId(docId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunk;
    }

    private static KnowledgeDoc doc(Long id, String allowedRoles) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setKnowledgeBaseId(9L);
        doc.setSpaceId(1L);
        doc.setName("doc-" + id);
        doc.setAllowedRoles(allowedRoles);
        return doc;
    }
}
