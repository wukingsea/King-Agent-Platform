package com.king.ai.knowledge.rerank;

import com.king.ai.knowledge.dto.RetrievedChunk;

import java.util.List;

/**
 * 检索候选重排端口：输入按融合分数粗排的候选，输出按相关性精排的列表。
 * 实现必须是无状态、可降级的——失败时调用方保留原顺序。
 */
public interface RerankClient {

    /**
     * 对候选重新排序。返回列表必须包含全部输入元素（顺序可变）；
     * 不允许增删元素，调用方依赖这一点做后续截断与去重。
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates);
}
