package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AuditServiceTenantAttributionTest {
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void stableAuthenticatedUserIdSelectsCorrectActorWhenUsernameExistsInMultipleTenants() {
        UUID authenticatedUserId = UUID.randomUUID();
        UUID otherTenantUserId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        User authenticatedUser = user(authenticatedUserId, "tenant-a", companyId, "admin", "TST Administrator");
        User sameUsernameInOtherTenant = user(otherTenantUserId, "tenant-b", UUID.randomUUID(), "admin", "Other Administrator");
        assertThat(authenticatedUser.getUsername()).isEqualTo(sameUsernameInOtherTenant.getUsername());

        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin",
            new AuthenticatedTokenState(authenticatedUserId, 0L),
            List.of()
        ));
        when(userRepository.findByIdAndIsDeletedFalse(authenticatedUserId)).thenReturn(Optional.of(authenticatedUser));

        service().logInCurrentTransaction("DemandPlanningCycle", UUID.randomUUID(), "CREATE", "DPC-001");

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getFullName()).isEqualTo("TST Administrator");
        assertThat(saved.getValue().getDetails()).contains("\"user\":\"TST Administrator\"");
        verify(userRepository, never()).findByUsernameAndIsDeletedFalse("admin");
        verify(userRepository, never()).findByUsernameIgnoreCaseAndIsDeletedFalse("admin");
    }

    @Test
    void legacyPrincipalLookupIsLimitedToCurrentTenantAndCompany() {
        UUID companyId = UUID.randomUUID();
        User authenticatedUser = user(UUID.randomUUID(), "tenant-a", companyId, "admin", "TST Administrator");
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null, List.of())
        );
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
            "tenant-a", companyId, "admin"
        )).thenReturn(Optional.of(authenticatedUser));

        service().logInCurrentTransaction("DemandPlanningCycle", UUID.randomUUID(), "CREATE", "DPC-002");

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getFullName()).isEqualTo("TST Administrator");
        verify(userRepository).findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
            "tenant-a", companyId, "admin"
        );
    }

    private AuditService service() {
        return new AuditService(
            auditLogRepository,
            new AuditSnapshotSerializer(new ObjectMapper().findAndRegisterModules()),
            userRepository
        );
    }

    private User user(UUID id, String tenantId, UUID companyId, String username, String fullName) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setCompanyId(companyId);
        user.setUsername(username);
        user.setFullName(fullName);
        return user;
    }
}
