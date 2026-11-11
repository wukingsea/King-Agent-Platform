package com.king.ai.knowledge.rerank;

import com.king.ai.knowledge.dto.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认重排实现：向量相似度与词项重叠度的加权融合。
 * 分词同时覆盖英文/数字词与中文连续片段的二元组，对中英混合查询都有效；
 * 纯本地计算、零外部依赖，结果确定可复现。
 */
@Component
@ConditionalOnProperty(prefix = "king-ai.rag.rerank", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalRerankClient implements RerankClient {

    private static final double VECTOR_WEIGHT = 0.7;
    private static final double LEXICAL_WEIGHT = 0.3;
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return candidates;
        }
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return candidates;
        }
        List<Indexed> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            RetrievedChunk chunk = candidates.get(i);
            double lexical = overlapRatio(queryTerms, terms(chunk.content()));
            double fused = VECTOR_WEIGHT * clamp01(chunk.score()) + LEXICAL_WEIGHT * lexical;
            scored.add(new Indexed(i, chunk, fused));
        }
        scored.sort(Comparator.comparingDouble(Indexed::score).reversed()
                .thenComparingInt(Indexed::index));
        return scored.stream().map(Indexed::chunk).toList();
    }

    private static double overlapRatio(Set<String> queryTerms, Set<String> docTerms) {
        if (docTerms.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String term : queryTerms) {
            if (docTerms.contains(term)) {
                hits++;
            }
        }
        return (double) hits / queryTerms.size();
    }

    private static double clamp01(double score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 1.0);
    }

    /**
     * 提取检索词项：英文/数字连续串按词切分；中文连续串按相邻二元组切分
     * （单字中文区分度太低）。全部小写归一化。
     */
    static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher word = WORD.matcher(lower);
        while (word.find()) {
            terms.add(word.group());
        }
        Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                terms.add(run);
                continue;
            }
            for (int i = 0; i < run.length() - 1; i++) {
                terms.add(run.substring(i, i + 2));
            }
        }
        return terms;
    }

    private record Indexed(int index, RetrievedChunk chunk, double score) {
    }
}
