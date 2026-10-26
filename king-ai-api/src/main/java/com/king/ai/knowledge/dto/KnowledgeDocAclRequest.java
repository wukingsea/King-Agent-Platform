package com.king.ai.knowledge.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record KnowledgeDocAclRequest(
        @Size(max = 4, message = "最多配置 4 个角色")
        List<String> allowedRoles
) {
}
