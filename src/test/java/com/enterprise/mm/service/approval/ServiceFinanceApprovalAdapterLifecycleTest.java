package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ServiceOrderAcceptanceLifecycleService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceFinanceApprovalAdapterLifecycleTest {
    @Mock private ServiceAcceptanceActRepository actRepository;
    @Mock private ServiceOrderRepository orderRepository;
    @Mock private AuditService auditService;
    @Mock private ServiceReviewHistoryService historyService;

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
    void financeApprovalsSynchronizePartialAndFullOrderCoverage() {
        ServiceOrder order = order();
        ServiceAcceptanceAct first = act(order, BigDecimal.valueOf(40));
        ServiceAcceptanceAct second = act(order, BigDecimal.valueOf(60));
        when(actRepository.findByIdAndIsDeletedFalse(first.getId())).thenReturn(Optional.of(first));
        when(actRepository.findByIdAndIsDeletedFalse(second.getId())).thenReturn(Optional.of(second));
        when(orderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(actRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(List.of(first, second));
        when(actRepository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ServiceOrderAcceptanceLifecycleService lifecycle =
            new ServiceOrderAcceptanceLifecycleService(actRepository, orderRepository);
        ServiceFinanceApprovalAdapter adapter =
            new ServiceFinanceApprovalAdapter(actRepository, auditService, historyService, lifecycle);

        adapter.markApproved(first.getId(), "first stage approved");
        assertThat(order.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");

        adapter.markApproved(second.getId(), "final stage approved");
        assertThat(order.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void financeRejectionTriggersOrderStatusRecomputation() {
        ServiceOrder order = order();
        ServiceAcceptanceAct act = act(order, BigDecimal.valueOf(100));
        ServiceOrderAcceptanceLifecycleService lifecycle = mock(ServiceOrderAcceptanceLifecycleService.class);
        when(actRepository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(actRepository.save(act)).thenReturn(act);
        ServiceFinanceApprovalAdapter adapter =
            new ServiceFinanceApprovalAdapter(actRepository, auditService, historyService, lifecycle);

        adapter.markRejected(act.getId(), "incorrect amount");

        assertThat(act.getStatus()).isEqualTo("REJECTED");
        verify(lifecycle).recompute(order.getId());
    }

    private ServiceOrder order() {
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-FINANCE");
        order.setName("Finance order");
        order.setStatus("ACT_PENDING");
        order.setTotalAmount(BigDecimal.valueOf(100));
        return order;
    }

    private ServiceAcceptanceAct act(ServiceOrder order, BigDecimal amount) {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        act.setServiceOrderId(order.getId());
        act.setCode("ACT-FINANCE");
        act.setName("Finance act");
        act.setStatus("FINANCE_REVIEW");
        act.setAmount(amount);
        return act;
    }
}
