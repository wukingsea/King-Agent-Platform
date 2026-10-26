package com.king.ai.knowledge.vector;

public record VectorRecord(long chunkId, long docId, float[] embedding) {
}
