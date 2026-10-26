package com.king.ai.llm;

public class LlmCancelledException extends RuntimeException {

    public LlmCancelledException() {
        super("生成已取消");
    }
}
