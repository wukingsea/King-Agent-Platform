package com.king.ai.auth.dto;

public record MfaSetupResponse(String secret, String otpauthUrl) {
}
