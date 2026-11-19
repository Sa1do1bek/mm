package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.PasswordSecurityException;
import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {
    private static final String TENANT = "tenant-password";
    private static final String OLD_PASSWORD = "old-password-value";
    private static final String NEW_PASSWORD = "new-password-value";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    private PasswordChangeService service;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(TENANT);
        user.setCompanyId(companyId);
        user.setUsername("worker");
        user.setPasswordHash("old-hash");
        user.setTokenVersion(6);
        authentication = new UsernamePasswordAuthenticationToken(
            "worker",
            new AuthenticatedTokenState(user.getId(), user.getTokenVersion()),
            List.of()
        );
        service = new PasswordChangeService(
            userRepository,
            passwordEncoder,
            new PasswordPolicy(),
            auditService
        );
        org.mockito.Mockito.lenient().when(
            userRepository.findCurrentUserForPasswordChange(user.getId(), TENANT, "worker")
        )
            .thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void changesPasswordInTheLockedTransactionAndIncrementsVersionOnce() {
        when(passwordEncoder.matches(OLD_PASSWORD, "old-hash")).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, "old-hash")).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");

        service.changePassword(
            authentication,
            new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        );

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(7);
        verify(auditService).logInCurrentTransaction(
            "User",
            user.getId(),
            "PASSWORD_CHANGED",
            "actorUserId=" + user.getId()
        );
        verify(userRepository).flush();
    }

    @Test
    void checksConfirmationBeforePolicyOrCurrentPassword() {
        assertThatThrownBy(() -> service.changePassword(
            authentication,
            new ChangePasswordRequest(OLD_PASSWORD, "short", "different")
        )).isInstanceOfSatisfying(PasswordSecurityException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo("PASSWORD_CONFIRMATION_MISMATCH")
        );

        verify(passwordEncoder, never()).matches(OLD_PASSWORD, "old-hash");
    }

    @Test
    void rejectsAnInvalidCurrentPasswordWithoutMutatingCredentialState() {
        when(passwordEncoder.matches(OLD_PASSWORD, "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
            authentication,
            new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        )).isInstanceOfSatisfying(PasswordSecurityException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo("CURRENT_PASSWORD_INVALID")
        );

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        assertThat(user.getTokenVersion()).isEqualTo(6);
        verify(auditService, never()).logInCurrentTransaction(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void rejectsAReplacementThatMatchesTheStoredCredential() {
        when(passwordEncoder.matches(OLD_PASSWORD, "old-hash")).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(
            authentication,
            new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        )).isInstanceOfSatisfying(PasswordSecurityException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo("NEW_PASSWORD_SAME_AS_OLD")
        );
    }

    @Test
    void propagatesAuditFailureSoTheTransactionCanRollBack() {
        when(passwordEncoder.matches(OLD_PASSWORD, "old-hash")).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, "old-hash")).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        doThrow(new IllegalStateException("audit unavailable")).when(auditService)
            .logInCurrentTransaction(
                "User",
                user.getId(),
                "PASSWORD_CHANGED",
                "actorUserId=" + user.getId()
            );

        assertThatThrownBy(() -> service.changePassword(
            authentication,
            new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        )).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).flush();
    }
}
