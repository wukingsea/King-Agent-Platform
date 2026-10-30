package com.king.ai.auth.dto;

import java.util.List;

public record MfaEnableResponse(List<String> recoveryCodes) {
}
