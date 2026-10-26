package com.king.ai.llm;

public interface LlmStreamListener {

    void onReasoning(String delta);

    void onContent(String delta);
}
