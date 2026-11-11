package com.king.ai.chat;

/** 工具执行结果：文本内容与是否为错误。 */
public record ToolOutcome(String text, boolean error) {
}
