package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementCloseoutAuditEvent;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.ProcurementCloseoutAuditEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementCloseoutAuditHistoryServiceTest {
    @Mock private ProcurementCloseoutAuditEventRepository repository;
    @Mock private NotificationService notificationService;
    @InjectMocks private ProcurementCloseoutAuditHistoryService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void recordSkipsNotificationWhenIdempotencyKeyAlreadyExists() {
        ProcurementCloseout closeout = closeout();
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            eq("default"), eq(CompanyContext.DEFAULT_COMPANY_ID), any())).thenReturn(Optional.of(new ProcurementCloseoutAuditEvent()));

        service.record(closeout, "FINALIZE", "retry", true);

        verify(repository, never()).save(any());
        verify(notificationService, never()).createForRole(eq(RoleName.PROCUREMENT_MANAGER), any(), any(), any(), any(), any());
    }

    @Test
    void recordPersistsAuditAndNotifiesProcurementManagerOnce() {
        ProcurementCloseout closeout = closeout();
        when(repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            eq("default"), eq(CompanyContext.DEFAULT_COMPANY_ID), any())).thenReturn(Optional.empty());

        service.record(closeout, "FINALIZE", "closed", true);

        verify(repository).save(any(ProcurementCloseoutAuditEvent.class));
        verify(notificationService).createForRole(eq(RoleName.PROCUREMENT_MANAGER), any(), any(), any(), eq("ProcurementCloseout"), eq(closeout.getId()));
    }

    private ProcurementCloseout closeout() {
        ProcurementCloseout closeout = new ProcurementCloseout();
        closeout.setId(UUID.randomUUID());
        closeout.setTenantId("default");
        closeout.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        closeout.setCode("PROC-CLOSEOUT-1");
        closeout.setName("Closeout");
        closeout.setProcurementProcedureId(UUID.randomUUID());
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CLOSED);
        return closeout;
    }
}
