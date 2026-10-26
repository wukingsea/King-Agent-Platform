package com.king.ai.llm;

import java.util.List;

public record LlmResult(
        String content,
        String reasoning,
        List<ToolCall> toolCalls,
        String finishReason,
        int promptTokens,
        int completionTokens
) {
}
