package com.king.ai.agent.release.dto;

import com.king.ai.agent.entity.AgentEvalCase;

import java.time.LocalDateTime;

public record AgentEvalCaseView(
        Long id,
        Long agentId,
        String name,
        String prompt,
        String expectedKeywords,
        Integer weight,
        Integer status,
        LocalDateTime createdAt
) {

    public static AgentEvalCaseView of(AgentEvalCase evalCase) {
        return new AgentEvalCaseView(evalCase.getId(), evalCase.getAgentId(), evalCase.getName(),
                evalCase.getPrompt(), evalCase.getExpectedKeywords(), evalCase.getWeight(),
                evalCase.getStatus(), evalCase.getCreatedAt());
    }
}
