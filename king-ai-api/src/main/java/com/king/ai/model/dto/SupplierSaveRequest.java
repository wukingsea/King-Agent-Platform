package com.king.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupplierSaveRequest(
        @NotBlank(message = "供应商名称不能为空")
        @Size(max = 80, message = "供应商名称不能超过 80 个字符")
        String name,

        @NotBlank(message = "供应商类型不能为空")
        @Pattern(regexp = "(?i)MOCK|OPENAI", message = "供应商类型不合法")
        String type,

        @Size(max = 500, message = "调用地址不能超过 500 个字符")
        String baseUrl,

        @Size(max = 2000, message = "API Key 不能超过 2000 个字符")
        String apiKey,

        @Size(max = 8000, message = "自定义请求头不能超过 8000 个字符")
        String headers,

        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
