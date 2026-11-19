package com.enterprise.mm.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, secret-safe API errors for credential lifecycle operations.
 */
public class PasswordSecurityException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    private PasswordSecurityException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static PasswordSecurityException currentPasswordInvalid() {
        return new PasswordSecurityException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "CURRENT_PASSWORD_INVALID",
            "Current password is invalid"
        );
    }

    public static PasswordSecurityException confirmationMismatch() {
        return new PasswordSecurityException(
            HttpStatus.BAD_REQUEST,
            "PASSWORD_CONFIRMATION_MISMATCH",
            "New password confirmation does not match"
        );
    }

    public static PasswordSecurityException sameAsOld() {
        return new PasswordSecurityException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "NEW_PASSWORD_SAME_AS_OLD",
            "New password must differ from the current password"
        );
    }

    public static PasswordSecurityException policyViolation() {
        return new PasswordSecurityException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "PASSWORD_POLICY_VIOLATION",
            "New password does not meet the password policy"
        );
    }

    public static PasswordSecurityException authenticationRequired() {
        return new PasswordSecurityException(
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "Authentication required"
        );
    }

    public static PasswordSecurityException tokenRevoked() {
        return new PasswordSecurityException(
            HttpStatus.UNAUTHORIZED,
            "TOKEN_REVOKED",
            "Authentication token has been revoked"
        );
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
