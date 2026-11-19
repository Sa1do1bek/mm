package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PasswordSecurityErrorContractTest {
    @Test
    void exposesTheApprovedStableCodesAndStatusesWithoutSecretValues() {
        assertError(
            PasswordSecurityException.currentPasswordInvalid(),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "CURRENT_PASSWORD_INVALID"
        );
        assertError(
            PasswordSecurityException.confirmationMismatch(),
            HttpStatus.BAD_REQUEST,
            "PASSWORD_CONFIRMATION_MISMATCH"
        );
        assertError(
            PasswordSecurityException.sameAsOld(),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "NEW_PASSWORD_SAME_AS_OLD"
        );
        assertError(
            PasswordSecurityException.policyViolation(),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "PASSWORD_POLICY_VIOLATION"
        );
        assertError(
            PasswordSecurityException.authenticationRequired(),
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED"
        );
        assertError(
            PasswordSecurityException.tokenRevoked(),
            HttpStatus.UNAUTHORIZED,
            "TOKEN_REVOKED"
        );
    }

    private void assertError(
        PasswordSecurityException exception,
        HttpStatus status,
        String code
    ) {
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getErrorCode()).isEqualTo(code);
        assertThat(exception.getMessage()).doesNotContain(
            "current-password-value",
            "new-password-value",
            "Bearer ",
            "$2a$"
        );
    }
}
