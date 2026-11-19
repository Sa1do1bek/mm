package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.PasswordSecurityException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsExactlyEightLiteralCharactersWithoutTrimming() {
        assertThatCode(() -> policy.validateNewPassword("       a"))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsFewerThanEightUnicodeCharacters() {
        assertThatThrownBy(() -> policy.validateNewPassword("short!?"))
            .isInstanceOfSatisfying(PasswordSecurityException.class, exception -> {
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("PASSWORD_POLICY_VIOLATION");
            });
    }

    @Test
    void acceptsExactlySeventyTwoUtf8BytesAndRejectsMore() {
        assertThatCode(() -> policy.validateNewPassword("é".repeat(36)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateNewPassword("é".repeat(37)))
            .isInstanceOfSatisfying(PasswordSecurityException.class, exception -> {
                org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                    .isEqualTo("PASSWORD_POLICY_VIOLATION");
            });
    }
}
