package com.king.ai.knowledge.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.ChunkVector;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.mapper.ChunkVectorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "king-ai", name = "vector-store", havingValue = "local")
public class LocalVectorStore implements VectorStore {

    private final ChunkVectorMapper chunkVectorMapper;
    private final KingAiProperties properties;

    @Override
    public void ensureCollection(KnowledgeBase base) {
    }

    @Override
    public String storageName(KnowledgeBase base) {
        return "king_ai_chunk_vector";
    }

    @Override
    public void upsert(KnowledgeBase base, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (VectorRecord record : records) {
            ChunkVector existing = chunkVectorMapper.selectById(record.chunkId());
            if (existing == null) {
                ChunkVector vector = new ChunkVector();
                vector.setChunkId(record.chunkId());
                vector.setSpaceId(base.getSpaceId());
                vector.setKnowledgeBaseId(base.getId());
                vector.setDocId(record.docId());
                vector.setEmbedding(encode(record.embedding()));
                chunkVectorMapper.insert(vector);
            } else {
                existing.setEmbedding(encode(record.embedding()));
                chunkVectorMapper.updateById(existing);
            }
        }
    }

    @Override
    public void deleteDoc(KnowledgeBase base, Long docId) {
        chunkVectorMapper.delete(new LambdaQueryWrapper<ChunkVector>()
                .eq(ChunkVector::getKnowledgeBaseId, base.getId())
                .eq(ChunkVector::getDocId, docId));
    }

    @Override
    public void deleteCollection(KnowledgeBase base) {
        chunkVectorMapper.delete(new LambdaQueryWrapper<ChunkVector>()
                .eq(ChunkVector::getKnowledgeBaseId, base.getId()));
    }

    /**
     * Brute-force cosine search over the knowledge base. Vectors are streamed in keyset pages
     * while a bounded heap keeps the best top-K hits, so memory stays proportional to topK
     * instead of the number of chunks, and no row is silently skipped.
     */
    @Override
    public List<VectorHit> search(KnowledgeBase base, float[] query, int topK) {
        return search(base, query, topK, java.util.Set.of());
    }

    @Override
    public List<VectorHit> search(KnowledgeBase base, float[] query, int topK,
                                  java.util.Set<Long> deniedDocIds) {
        if (query == null || query.length == 0 || topK <= 0) {
            return List.of();
        }
        int batchSize = Math.max(1, properties.getLocalVector().getScanBatchSize());
        int maxScan = properties.getLocalVector().getMaxScan();
        PriorityQueue<VectorHit> best = new PriorityQueue<>(Math.max(1, topK),
                Comparator.comparingDouble(VectorHit::score));
        long cursor = 0L;
        int scanned = 0;
        boolean capped = false;
        while (true) {
            int limit = batchSize;
            if (maxScan > 0) {
                int remaining = maxScan - scanned;
                if (remaining <= 0) {
                    capped = true;
                    break;
                }
                limit = Math.min(limit, remaining);
            }
            List<ChunkVector> batch = chunkVectorMapper.scanBatch(base.getId(), cursor, limit);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (ChunkVector entity : batch) {
                if (deniedDocIds != null && !deniedDocIds.isEmpty()
                        && deniedDocIds.contains(entity.getDocId())) {
                    continue;
                }
                float[] embedding = decode(entity.getEmbedding());
                if (embedding == null || embedding.length != query.length) {
                    continue;
                }
                double score = cosine(query, embedding);
                if (Double.isNaN(score)) {
                    continue;
                }
                offer(best, new VectorHit(entity.getChunkId(), score), topK);
            }
            scanned += batch.size();
            cursor = batch.get(batch.size() - 1).getChunkId();
            if (batch.size() < limit) {
                break;
            }
        }
        if (capped) {
            log.warn("Local vector scan capped at {} rows: kbId={}", maxScan, base.getId());
        }
        List<VectorHit> hits = new ArrayList<>(best);
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return hits;
    }

    private static void offer(PriorityQueue<VectorHit> best, VectorHit hit, int topK) {
        if (best.size() < topK) {
            best.add(hit);
        } else if (hit.score() > best.peek().score()) {
            best.poll();
            best.add(hit);
        }
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        double denominator = Math.sqrt(leftNorm) * Math.sqrt(rightNorm);
        return denominator < 1e-9 ? 0 : dot / denominator;
    }

    private static String encode(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.toString();
    }

    private static float[] decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(",");
        float[] vector = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i]);
            }
            return vector;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
