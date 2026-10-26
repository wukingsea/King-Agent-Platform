package com.king.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicChatSendRequest(
        @NotBlank(message = "缺少智能体标识") String publicId,
        Long sessionId,
        @NotBlank(message = "请输入消息内容")
        @Size(max = 10000, message = "消息内容不能超过 10000 个字符") String message
) {
}
