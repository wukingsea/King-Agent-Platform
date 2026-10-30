package com.king.ai.auth;

import com.king.ai.common.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordRulesTest {

    @Test
    void acceptsPasswordWithUpperLowerAndDigit() {
        assertThatCode(() -> PasswordRules.validate("KingAi2026")).doesNotThrowAnyException();
    }

    @Test
    void rejectsNullAndShortPasswords() {
        assertThatThrownBy(() -> PasswordRules.validate(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PasswordRules.validate("Ab1")).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsMissingCharacterClasses() {
        assertThatThrownBy(() -> PasswordRules.validate("abcdefg1")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PasswordRules.validate("ABCDEFG1")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PasswordRules.validate("Abcdefgh")).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsPasswordsLongerThanSeventyTwoCharacters() {
        String password = "Aa1" + "x".repeat(70);
        assertThatThrownBy(() -> PasswordRules.validate(password)).isInstanceOf(BizException.class);
    }
}
