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

    /**
     * Vector search with document-level ACL pushdown: chunks belonging to
     * {@code deniedDocIds} must not be returned. Stores that cannot express the
     * filter natively may ignore it only if they still guarantee exclusion
     * upstream; callers always re-check ACL after hydration as a safety net.
     */
    default List<VectorHit> search(KnowledgeBase base, float[] query, int topK,
                                   java.util.Set<Long> deniedDocIds) {
        return search(base, query, topK);
    }

    /**
     * Readiness probe for the backing store. Local (database-backed) implementations do not need
     * an extra round trip because the database probe already covers them.
     */
    default boolean healthy() {
        return true;
    }
}
