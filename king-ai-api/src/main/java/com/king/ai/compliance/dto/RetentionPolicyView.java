package com.king.ai.compliance.dto;

public record RetentionPolicyView(Long id, String resourceType, Integer retentionDays,
                                  boolean enabled, boolean archiveBeforeDelete) {
}
