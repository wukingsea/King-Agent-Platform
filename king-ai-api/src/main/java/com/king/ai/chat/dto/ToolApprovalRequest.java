package com.king.ai.chat.dto;

import jakarta.validation.constraints.NotNull;

public record ToolApprovalRequest(@NotNull(message = "请选择是否批准工具调用") Boolean approved) {
}
