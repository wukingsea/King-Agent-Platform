package com.king.ai.auth;

import com.king.ai.common.BizException;

/**
 * Password composition rules shared by every password-writing path (self-service change,
 * admin reset and user creation) so the API and the UI cannot drift apart.
 */
public final class PasswordRules {

    public static final String REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$";
    public static final String MESSAGE = "密码需为 8-72 位，且同时包含大写字母、小写字母和数字";

    private PasswordRules() {
    }

    public static void validate(String password) {
        if (password == null || !password.matches(REGEX)) {
            throw BizException.badRequest(MESSAGE);
        }
    }
}
