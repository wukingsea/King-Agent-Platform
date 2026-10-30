package com.king.ai.agent;

import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.entity.AgentEvalCase;
import com.king.ai.config.KingAiProperties;
import com.king.ai.llm.CancellationToken;
import com.king.ai.llm.LlmChatRequest;
import com.king.ai.llm.LlmMessage;
import com.king.ai.llm.LlmResult;
import com.king.ai.model.ModelRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private static final int SUMMARY_LIMIT = 2000;

    private final ModelRuntimeService modelRuntimeService;
    private final KingAiProperties properties;

    public record Outcome(int total, int passed, BigDecimal score, String summary) {
    }

    /**
     * Runs every enabled evaluation case against the given snapshot with one model call per
     * case. A case passes only when all expected keywords are present; the weighted score uses
     * the keyword hit ratio so partial answers still produce a comparable number.
     */
    public Outcome evaluate(Agent agent, AgentSnapshot snapshot, List<AgentEvalCase> cases) {
        if (cases.isEmpty()) {
            return new Outcome(0, 0, null, "未配置评测用例");
        }
        ModelRuntimeService.ResolvedModel resolved =
                modelRuntimeService.resolveChat(agent.getSpaceId(), snapshot.modelId());
        List<String> errors = new ArrayList<>();
        double weighted = 0;
        double weightSum = 0;
        int passed = 0;
        for (AgentEvalCase evalCase : cases) {
            int weight = evalCase.getWeight() == null || evalCase.getWeight() < 1 ? 1 : evalCase.getWeight();
            weightSum += weight;
            double ratio = 0;
            try {
                String answer = ask(resolved, snapshot, evalCase.getPrompt());
                ratio = hitRatio(answer, evalCase.getExpectedKeywords());
            } catch (RuntimeException ex) {
                log.warn("Evaluation case {} failed: {}", evalCase.getId(), ex.getMessage());
                errors.add(evalCase.getName() + "：" + ex.getClass().getSimpleName());
            }
            weighted += weight * ratio;
            if (ratio >= 1 - 1e-9) {
                passed++;
            }
        }
        double score = weightSum == 0 ? 0 : weighted / weightSum;
        BigDecimal rounded = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        String summary = passed + "/" + cases.size() + " 用例通过，加权得分 "
                + rounded.toPlainString();
        if (!errors.isEmpty()) {
            summary = summary + "；失败：" + String.join("；", errors);
        }
        if (summary.length() > SUMMARY_LIMIT) {
            summary = summary.substring(0, SUMMARY_LIMIT);
        }
        return new Outcome(cases.size(), passed, rounded, summary);
    }

    private String ask(ModelRuntimeService.ResolvedModel resolved, AgentSnapshot snapshot, String prompt) {
        List<LlmMessage> messages = new ArrayList<>(2);
        if (snapshot.systemPrompt() != null && !snapshot.systemPrompt().isBlank()) {
            messages.add(LlmMessage.system(snapshot.systemPrompt()));
        }
        messages.add(LlmMessage.user(prompt));
        LlmChatRequest request = new LlmChatRequest(resolved.model().getModel(), messages, List.of(),
                snapshot.temperature(), properties.getRelease().getEvalMaxTokens());
        LlmResult result = resolved.client().streamChat(request, null, new CancellationToken());
        return result == null ? "" : result.content();
    }

    public static double hitRatio(String answer, String expectedKeywords) {
        List<String> keywords = splitKeywords(expectedKeywords);
        if (keywords.isEmpty()) {
            return 0;
        }
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        long hits = keywords.stream()
                .filter(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
        return (double) hits / keywords.size();
    }

    static List<String> splitKeywords(String expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isBlank()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (String part : expectedKeywords.split("[,，、;；\\n]")) {
            String value = part.trim();
            if (!value.isEmpty() && !keywords.contains(value)) {
                keywords.add(value);
            }
        }
        return keywords;
    }
}
