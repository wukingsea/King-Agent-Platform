package com.king.ai.license.dto;

import java.time.Instant;
import java.util.List;

/**
 * License state exposed to administrators: claims, limits and live usage counts.
 */
public record LicenseStatusView(
        boolean enabled,
        String status,
        String licenseId,
        String customer,
        String edition,
        Instant issuedAt,
        Instant expiresAt,
        Limits limits,
        Usage usage,
        List<String> features) {

    public record Limits(int maxSpaces, int maxUsers, int maxAgents) {
    }

    public record Usage(long spaces, long users, long agents) {
    }
}
