package com.king.ai.knowledge;

import com.king.ai.common.BizException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.model.ModelRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final ModelRuntimeService modelRuntimeService;
    private final KingAiProperties properties;

    public List<float[]> embed(KnowledgeBase base, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ModelRuntimeService.ResolvedModel resolved = modelRuntimeService.resolveEmbedding(
                base.getSpaceId(), base.getEmbeddingModelId());
        int batchSize = Math.max(1, properties.getRag().getEmbeddingBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<String> batch = texts.subList(start, end);
            List<float[]> batchVectors = resolved.client().embed(resolved.model().getModel(), batch);
            if (batchVectors.size() != batch.size()) {
                throw new BizException(500, "向量服务返回数量与请求不一致");
            }
            for (float[] vector : batchVectors) {
                if (base.getEmbeddingDim() != null && base.getEmbeddingDim() > 0
                        && vector.length != base.getEmbeddingDim()) {
                    throw new BizException(500, "向量维度与知识库配置不一致，期望 "
                            + base.getEmbeddingDim() + "，实际 " + vector.length);
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }

    public float[] embedQuery(KnowledgeBase base, String query) {
        List<float[]> vectors = embed(base, List.of(query));
        if (vectors.isEmpty()) {
            throw new BizException(500, "向量服务未返回查询向量");
        }
        return vectors.get(0);
    }
}
