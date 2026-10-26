package com.king.ai.llm;

import java.util.List;

public interface LlmClient {

    LlmResult streamChat(LlmChatRequest request, LlmStreamListener listener, CancellationToken token);

    List<float[]> embed(String model, List<String> inputs);

    List<String> listModels();
}
