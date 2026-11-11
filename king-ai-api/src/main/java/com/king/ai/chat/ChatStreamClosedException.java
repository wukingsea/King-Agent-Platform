package com.king.ai.chat;

/**
 * SSE 连接已断开：发送失败时抛出，由 {@code ChatRuntime.run} 捕获后
 * 将会话标记为已停止，而不是当作生成失败。
 */
class ChatStreamClosedException extends RuntimeException {

    ChatStreamClosedException(Throwable cause) {
        super(cause);
    }
}
