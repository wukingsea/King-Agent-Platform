package com.king.ai.agent;

import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.entity.AgentEvalCase;
import com.king.ai.config.KingAiProperties;
import com.king.ai.llm.LlmChatRequest;
import com.king.ai.llm.LlmClient;
import com.king.ai.llm.LlmResult;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.entity.SupplierModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

    @Test
    void keywordRatioUsesEveryDelimiterAndIsCaseInsensitive() {
        assertThat(EvaluationService.hitRatio("退款需要 7 个工作日，请联系客服",
                "7 个工作日,联系客服，退款")).isEqualTo(1.0);
        assertThat(EvaluationService.hitRatio("只有退款", "退款,发票")).isEqualTo(0.5);
        assertThat(EvaluationService.hitRatio("", "退款")).isZero();
        assertThat(EvaluationService.hitRatio("anything", " ")).isZero();
    }

    @Test
    void evaluationWeightsPartialAnswersAndCountsOnlyPerfectCasesAsPassed() {
        ModelRuntimeService runtime = mock(ModelRuntimeService.class);
        LlmClient client = mock(LlmClient.class);
        SupplierModel model = new SupplierModel();
        model.setModel("gpt-test");
        Supplier supplier = new Supplier();
        when(runtime.resolveChat(2L, 5L))
                .thenReturn(new ModelRuntimeService.ResolvedModel(model, supplier, client));
        when(client.streamChat(any(LlmChatRequest.class), any(), any()))
                .thenReturn(new LlmResult("支持七天无理由退款，请联系客服", null, List.of(),
                        "stop", 1, 1))
                .thenReturn(new LlmResult("不支持", null, List.of(), "stop", 1, 1));

        KingAiProperties properties = new KingAiProperties();
        EvaluationService service = new EvaluationService(runtime, properties);
        Agent agent = new Agent();
        agent.setSpaceId(2L);
        AgentSnapshot snapshot = new AgentSnapshot("agent", "", 5L, "prompt",
                new BigDecimal("0.7"), 256, null, "WRITE", null,
                List.of(), List.of(), List.of(), null);

        EvaluationService.Outcome outcome = service.evaluate(agent, snapshot,
                List.of(evalCase("退款", "退款,客服", 1), evalCase("发票", "发票", 3)));

        assertThat(outcome.total()).isEqualTo(2);
        assertThat(outcome.passed()).isEqualTo(1);
        // 1*1.0 + 3*0.0 divided by 4 total weight
        assertThat(outcome.score()).isEqualByComparingTo("0.2500");
        assertThat(outcome.summary()).contains("1/2");
    }

    @Test
    void noCasesMeansNoScore() {
        EvaluationService service = new EvaluationService(mock(ModelRuntimeService.class),
                new KingAiProperties());
        EvaluationService.Outcome outcome = service.evaluate(new Agent(), snapshot(), List.of());

        assertThat(outcome.total()).isZero();
        assertThat(outcome.score()).isNull();
        assertThat(outcome.summary()).contains("未配置");
    }

    private static AgentEvalCase evalCase(String name, String keywords, int weight) {
        AgentEvalCase evalCase = new AgentEvalCase();
        evalCase.setName(name);
        evalCase.setPrompt(name);
        evalCase.setExpectedKeywords(keywords);
        evalCase.setWeight(weight);
        return evalCase;
    }

    private static AgentSnapshot snapshot() {
        return new AgentSnapshot("agent", "", 5L, "prompt", new BigDecimal("0.7"), 256,
                null, "WRITE", null, List.of(), List.of(), List.of(), null);
    }
}
