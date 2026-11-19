package com.enterprise.mm.service;

import com.enterprise.mm.error.PasswordSecurityException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    static final int MIN_CHARACTERS = 8;
    static final int MAX_UTF8_BYTES = 72;

    public void validateNewPassword(String password) {
        if (password == null
            || password.codePointCount(0, password.length()) < MIN_CHARACTERS
            || password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw PasswordSecurityException.policyViolation();
        }
    }
}
