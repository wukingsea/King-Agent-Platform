package com.king.ai.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record AgentSaveRequest(
        @NotBlank(message = "智能体名称不能为空")
        @Size(max = 100, message = "智能体名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "介绍不能超过 500 个字符")
        String intro,

        @NotNull(message = "请选择对话模型")
        Long modelId,

        @Size(max = 50000, message = "系统提示词不能超过 50000 个字符")
        String systemPrompt,

        @DecimalMin(value = "0.0", message = "温度不能小于 0")
        @DecimalMax(value = "2.0", message = "温度不能大于 2")
        BigDecimal temperature,

        @Min(value = 1, message = "最大输出 Token 不能小于 1")
        @Max(value = 32768, message = "最大输出 Token 不能超过 32768")
        Integer maxTokens,

        Integer status,

        @Size(max = 50, message = "最多绑定 50 个知识库")
        List<Long> knowledgeBaseIds,

        @Size(max = 50, message = "最多绑定 50 个 MCP 服务")
        List<Long> mcpIds,

        @Size(max = 50, message = "最多绑定 50 个技能")
        List<Long> skillIds,

        @Size(max = 100, message = "最多配置 100 个工具")
        List<@Size(max = 120, message = "工具名称不能超过 120 个字符") String> allowedTools,

        @Pattern(regexp = "NONE|WRITE|ALL", message = "工具审批模式必须为 NONE、WRITE 或 ALL")
        String toolApprovalMode,

        Long fallbackModelId,

        @Min(value = 0, message = "历史窗口条数不能小于 0")
        @Max(value = 500, message = "历史窗口条数不能超过 500")
        Integer historyWindow
) {
}
