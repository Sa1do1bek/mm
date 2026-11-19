package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryPeriodClose;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.InventoryPeriodCloseMapper;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import com.enterprise.mm.repository.InventoryPeriodCloseRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryPeriodCloseCommandPolicyTest {
    @Mock InventoryPeriodCloseRepository repository;
    @Mock InventoryPeriodCloseMapper mapper;
    @Mock InventoryFinanceCalculationService calculationService;
    @Mock InventoryAccountingReconciliationRepository reconciliationRepository;
    @Mock InventoryAccountingReconciliationService reconciliationService;
    @Mock InventoryValuationReconciliationService valuationReconciliationService;
    @Mock FinancialAuthorizationService financialAuthorizationService;
    @Mock IntegrationEventService integrationEventService;
    @Mock AuditService auditService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-period-command");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void staleReopenIsRejectedBeforeMutation() {
        InventoryPeriodClose period = closedPeriod();
        when(financialAuthorizationService.hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")).thenReturn(true);
        when(repository.findByIdAndIsDeletedFalse(period.getId())).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service().reopen(period.getId(), 3L, "Correct late posting"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("version conflict");

        verify(repository, never()).save(any());
        assertThat(period.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void controlledReopenRequiresReasonAndEmitsAuditAndIntegrationImpact() {
        InventoryPeriodClose period = closedPeriod();
        InventoryPeriodCloseResponse response = new InventoryPeriodCloseResponse();
        response.setId(period.getId());
        response.setStatus("REOPENED");
        response.setVersion(5L);
        when(financialAuthorizationService.hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")).thenReturn(true);
        when(repository.findByIdAndIsDeletedFalse(period.getId())).thenReturn(Optional.of(period));
        when(repository.save(period)).thenReturn(period);
        when(mapper.toResponse(period)).thenReturn(response);

        var reopened = service().reopen(period.getId(), 4L, "Correct late posting");

        assertThat(reopened.getStatus()).isEqualTo("REOPENED");
        assertThat(period.getStatus()).isEqualTo("REOPENED");
        verify(integrationEventService).emit(
            eq("INVENTORY.PERIOD_REOPENED"),
            eq("InventoryPeriodClose"),
            eq(period.getId()),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any()
        );
        ArgumentCaptor<String> auditDetails = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(
            eq("InventoryPeriodClose"),
            eq(period.getId()),
            eq("REOPEN"),
            auditDetails.capture(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(auditDetails.getValue()).contains("Correct late posting");
    }

    @Test
    void shortReopenReasonFailsBeforeLookup() {
        when(financialAuthorizationService.hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")).thenReturn(true);

        assertThatThrownBy(() -> service().reopen(UUID.randomUUID(), 1L, "fix"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at least 5 characters");

        verify(repository, never()).findByIdAndIsDeletedFalse(any());
    }

    private InventoryPeriodClose closedPeriod() {
        InventoryPeriodClose period = new InventoryPeriodClose();
        period.setId(UUID.randomUUID());
        period.setTenantId(TenantContext.currentTenant());
        period.setCompanyId(companyId);
        period.setName("June close");
        period.setCode("IPC-2026-06");
        period.setStatus("CLOSED");
        period.setVersion(4L);
        period.setPeriodStart(LocalDate.of(2026, 6, 1));
        period.setPeriodEnd(LocalDate.of(2026, 6, 30));
        return period;
    }

    private InventoryPeriodCloseService service() {
        return new InventoryPeriodCloseService(
            repository,
            mapper,
            calculationService,
            reconciliationRepository,
            reconciliationService,
            valuationReconciliationService,
            financialAuthorizationService,
            integrationEventService,
            auditService,
            org.mockito.Mockito.mock(InventoryPeriodCloseActionPolicy.class)
        );
    }
}
