package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.error.PasswordSecurityException;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordChangeEncodingTest {
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void successfulChangeStoresOnlyEncodedReplacementAndInvalidatesTheOldSecret() {
        TenantContext.setTenant("tenant-encoding");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-encoding");
        user.setUsername("encoded-user");
        user.setEnabled(true);
        user.setPasswordHash(encoder.encode("old-password-value"));
        when(userRepository.findCurrentUserForPasswordChange(
            userId,
            "tenant-encoding",
            "encoded-user"
        )).thenReturn(Optional.of(user));
        var authentication = new UsernamePasswordAuthenticationToken(
            "encoded-user",
            new AuthenticatedTokenState(userId, 0),
            List.of()
        );
        PasswordChangeService service = new PasswordChangeService(
            userRepository,
            encoder,
            new PasswordPolicy(),
            auditService
        );

        service.changePassword(
            authentication,
            new ChangePasswordRequest(
                "old-password-value",
                "new-password-value",
                "new-password-value"
            )
        );

        assertThat(user.getPasswordHash()).doesNotContain("new-password-value");
        assertThat(encoder.matches("old-password-value", user.getPasswordHash())).isFalse();
        assertThat(encoder.matches("new-password-value", user.getPasswordHash())).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(1);
    }

    @Test
    void secondRequestAfterTheRowLockSeesTheChangedHashAndCannotIncrementAgain() {
        TenantContext.setTenant("tenant-concurrency");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-concurrency");
        user.setUsername("concurrent-user");
        user.setEnabled(true);
        user.setPasswordHash(encoder.encode("shared-old-password"));
        when(userRepository.findCurrentUserForPasswordChange(
            userId,
            "tenant-concurrency",
            "concurrent-user"
        )).thenReturn(Optional.of(user));
        var authentication = new UsernamePasswordAuthenticationToken(
            "concurrent-user",
            new AuthenticatedTokenState(userId, 0),
            List.of()
        );
        PasswordChangeService service = new PasswordChangeService(
            userRepository,
            encoder,
            new PasswordPolicy(),
            auditService
        );

        service.changePassword(
            authentication,
            new ChangePasswordRequest(
                "shared-old-password",
                "first-new-password",
                "first-new-password"
            )
        );
        assertThatThrownBy(() -> service.changePassword(
            authentication,
            new ChangePasswordRequest(
                "shared-old-password",
                "second-new-password",
                "second-new-password"
            )
        )).isInstanceOfSatisfying(PasswordSecurityException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo("CURRENT_PASSWORD_INVALID")
        );

        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(encoder.matches("first-new-password", user.getPasswordHash())).isTrue();
        verify(auditService, times(1)).logInCurrentTransaction(
            "User",
            userId,
            "PASSWORD_CHANGED",
            "actorUserId=" + userId
        );
    }
}
