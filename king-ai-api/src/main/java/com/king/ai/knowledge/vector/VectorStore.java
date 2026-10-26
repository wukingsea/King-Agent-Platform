package com.king.ai.knowledge.vector;

import com.king.ai.knowledge.entity.KnowledgeBase;

import java.util.List;

public interface VectorStore {

    void ensureCollection(KnowledgeBase base);

    void upsert(KnowledgeBase base, List<VectorRecord> records);

    void deleteDoc(KnowledgeBase base, Long docId);

    void deleteCollection(KnowledgeBase base);

    List<VectorHit> search(KnowledgeBase base, float[] query, int topK);
}
