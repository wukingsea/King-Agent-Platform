package com.king.ai.knowledge.rerank;

import com.king.ai.knowledge.dto.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRerankClientTest {

    private final LocalRerankClient client = new LocalRerankClient();

    @Test
    void lexicalOverlapPromotesRelevantChunkOverHigherVectorScore() {
        RetrievedChunk weakVector = chunk(1L, 0.95, "unrelated content about cooking recipes");
        RetrievedChunk strongLexical = chunk(2L, 0.70, "the refund policy covers returns within 30 days");

        List<RetrievedChunk> result = client.rerank("refund policy", List.of(weakVector, strongLexical));

        assertThat(result).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    void preservesAllCandidatesAndIsDeterministic() {
        RetrievedChunk a = chunk(1L, 0.5, "alpha beta gamma");
        RetrievedChunk b = chunk(2L, 0.5, "alpha beta gamma");
        RetrievedChunk c = chunk(3L, 0.5, "completely different words here");

        List<RetrievedChunk> first = client.rerank("alpha beta", List.of(a, b, c));
        List<RetrievedChunk> second = client.rerank("alpha beta", List.of(a, b, c));

        assertThat(first).hasSize(3);
        assertThat(first).containsExactlyElementsOf(second);
        // 同分时保持原始相对顺序（稳定排序）
        assertThat(first.subList(0, 2)).extracting(RetrievedChunk::chunkId)
                .containsExactly(1L, 2L);
    }

    @Test
    void emptyQueryTermsReturnInputUnchanged() {
        RetrievedChunk a = chunk(1L, 0.9, "x");
        RetrievedChunk b = chunk(2L, 0.8, "y");
        assertThat(client.rerank("  ", List.of(a, b))).containsExactly(a, b);
        assertThat(client.rerank(null, List.of(a, b))).containsExactly(a, b);
    }

    private static RetrievedChunk chunk(long id, double score, String content) {
        return new RetrievedChunk(id, 100L + id, "doc", 9L, "kb", 0, content, score);
    }
}
