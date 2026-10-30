package com.king.ai.license;

import java.time.Instant;
import java.util.List;

/**
 * Verified contents of a license file. Capacity limits at or below zero mean unlimited.
 */
public record LicenseClaims(
        String licenseId,
        String customer,
        String edition,
        Instant issuedAt,
        Instant expiresAt,
        int maxSpaces,
        int maxUsers,
        int maxAgents,
        List<String> features) {
}
