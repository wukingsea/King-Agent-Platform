package com.king.ai.agent.release.dto;

import jakarta.validation.constraints.Size;

public record ReleaseDecisionRequest(
        @Size(max = 1000, message = "审批意见不能超过 1000 个字符")
        String comment
) {
}
