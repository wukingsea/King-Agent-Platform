package com.king.ai.knowledge.vector;

import com.king.ai.knowledge.entity.KnowledgeBase;

import java.util.List;

public interface VectorStore {

    void ensureCollection(KnowledgeBase base);

    /**
     * Allocates or derives the storage identifier persisted on the knowledge base and shown to
     * operators. Shared stores return a name shared by several knowledge bases; per-KB stores
     * return a fresh name on every call, so it is only used when (re)initialising a base.
     */
    String storageName(KnowledgeBase base);

    void upsert(KnowledgeBase base, List<VectorRecord> records);

    void deleteDoc(KnowledgeBase base, Long docId);

    void deleteCollection(KnowledgeBase base);

    List<VectorHit> search(KnowledgeBase base, float[] query, int topK);
}
