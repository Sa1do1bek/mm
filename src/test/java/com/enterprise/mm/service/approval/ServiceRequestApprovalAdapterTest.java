package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.BudgetItemService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceRequestApprovalAdapterTest {
    @Mock private ServiceRequestRepository repository;
    @Mock private AuditService auditService;
    @Mock private ServiceReviewHistoryService historyService;
    @Mock private BudgetItemService budgetItemService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void markSubmittedReservesBudgetAndMovesToApprovalPending() {
        TenantContext.setTenant("default");
        UUID id = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = new ServiceRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCode("SR-1");
        request.setStatus(ServiceRequestStatus.DRAFT.name());
        request.setBudgetItemId(budgetItemId);
        request.setEstimatedAmount(BigDecimal.TEN);

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(request));
        when(repository.save(any(ServiceRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceRequestApprovalAdapter adapter = new ServiceRequestApprovalAdapter(repository, auditService, historyService, budgetItemService);
        adapter.markSubmitted(id);

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.APPROVAL_PENDING.name());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(request.getBudgetReservedAt()).isNotNull();
        verify(budgetItemService).reserve(budgetItemId, BigDecimal.TEN, "ServiceRequest SR-1");
    }

    @Test
    void markRejectedReleasesReservedBudget() {
        TenantContext.setTenant("default");
        UUID id = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = new ServiceRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCode("SR-2");
        request.setStatus(ServiceRequestStatus.APPROVAL_PENDING.name());
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(25));

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(request));
        when(repository.save(any(ServiceRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceRequestApprovalAdapter adapter = new ServiceRequestApprovalAdapter(repository, auditService, historyService, budgetItemService);
        adapter.markRejected(id, "reject");

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.REJECTED.name());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getBudgetReleasedAt()).isNotNull();
        verify(budgetItemService).release(budgetItemId, BigDecimal.valueOf(25), "ServiceRequest SR-2");
    }
}
