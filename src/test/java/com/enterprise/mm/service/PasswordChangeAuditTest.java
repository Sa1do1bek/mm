package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
class PasswordChangeAuditTest {
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        CompanyContext.clear();
    }

    @Test
    void passwordChangeAuditContainsOnlySafeActorAndTargetMetadata() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("audit-user", null, List.of())
        );
        AuditService service = new AuditService(
            auditLogRepository,
            new AuditSnapshotSerializer(new ObjectMapper().findAndRegisterModules()),
            userRepository
        );

        service.logInCurrentTransaction(
            "User",
            userId,
            "PASSWORD_CHANGED",
            "actorUserId=" + userId
        );

        ArgumentCaptor<AuditLog> log = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(log.capture());
        assertThat(log.getValue().getEntityId()).isEqualTo(userId);
        assertThat(log.getValue().getAction()).isEqualTo("PASSWORD_CHANGED");
        assertThat(log.getValue().getUsername()).isEqualTo("audit-user");
        assertThat(log.getValue().getCompanyId()).isEqualTo(companyId);
        assertThat(log.getValue().getDetails())
            .contains(userId.toString(), "audit-user")
            .doesNotContain(
                "oldPassword",
                "newPassword",
                "confirmNewPassword",
                "passwordHash",
                "authorization",
                "token"
            );
        assertThat(log.getValue().getOldSnapshot()).isNull();
        assertThat(log.getValue().getNewSnapshot()).isNull();
    }
}
