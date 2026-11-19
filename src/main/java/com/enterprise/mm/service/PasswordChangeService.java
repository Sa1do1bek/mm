package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.PasswordSecurityException;
import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordChangeService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;

    @Transactional
    public void changePassword(Authentication authentication, ChangePasswordRequest request) {
        requireAuthenticated(authentication);
        requireStructurallyComplete(request);
        if (!Objects.equals(request.getNewPassword(), request.getConfirmNewPassword())) {
            throw PasswordSecurityException.confirmationMismatch();
        }
        passwordPolicy.validateNewPassword(request.getNewPassword());

        if (!(authentication.getCredentials() instanceof AuthenticatedTokenState tokenState)) {
            throw PasswordSecurityException.authenticationRequired();
        }
        User user = userRepository.findCurrentUserForPasswordChange(
            tokenState.userId(),
            TenantContext.currentTenant(),
            authentication.getName()
        ).filter(User::isEnabled)
            .orElseThrow(PasswordSecurityException::authenticationRequired);

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw PasswordSecurityException.currentPasswordInvalid();
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw PasswordSecurityException.sameAsOld();
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setTokenVersion(Math.incrementExact(user.getTokenVersion()));
        auditService.logInCurrentTransaction(
            "User",
            user.getId(),
            "PASSWORD_CHANGED",
            "actorUserId=" + user.getId()
        );
        userRepository.flush();
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw PasswordSecurityException.authenticationRequired();
        }
    }

    private void requireStructurallyComplete(ChangePasswordRequest request) {
        if (request == null
            || request.getOldPassword() == null
            || request.getNewPassword() == null
            || request.getConfirmNewPassword() == null) {
            throw PasswordSecurityException.policyViolation();
        }
    }
}
