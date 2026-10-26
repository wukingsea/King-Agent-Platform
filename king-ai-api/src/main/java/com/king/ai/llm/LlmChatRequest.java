package com.king.ai.llm;

import java.math.BigDecimal;
import java.util.List;

public record LlmChatRequest(
        String model,
        List<LlmMessage> messages,
        List<ToolDefinition> tools,
        BigDecimal temperature,
        Integer maxTokens
) {
}
