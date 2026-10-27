package com.king.ai.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccessReviewDecisionRequest(@NotBlank String decision,
                                         @Size(max = 500) String note) {
}
