package com.king.ai.auth.dto;

import java.time.LocalDateTime;

public record MfaStatusView(boolean enabled, boolean pending, LocalDateTime enrolledAt, int recoveryCodesRemaining) {
}
