package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
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
class ServiceOrderAcceptanceLifecycleServiceTest {
    @Mock private ServiceAcceptanceActRepository actRepository;
    @Mock private ServiceOrderRepository orderRepository;

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
    void fullDraftCoverageLeavesOrderPendingInsteadOfAccepted() {
        ServiceOrder order = order(BigDecimal.valueOf(100));
        ServiceAcceptanceAct draft = act(order, "DRAFT", BigDecimal.valueOf(100));
        stub(order, List.of(draft));

        String status = service().recompute(order.getId());

        assertThat(status).isEqualTo("ACT_PENDING");
        assertThat(order.getStatus()).isEqualTo("ACT_PENDING");
        verify(orderRepository).save(order);
    }

    @Test
    void cumulativeFinanceApprovedActsMoveOrderFromPartialToAccepted() {
        ServiceOrder order = order(BigDecimal.valueOf(100));
        ServiceAcceptanceAct first = act(order, "APPROVED", BigDecimal.valueOf(40));
        ServiceAcceptanceAct second = act(order, "FINANCE_REVIEW", BigDecimal.valueOf(60));
        stub(order, List.of(first, second));

        assertThat(service().recompute(order.getId())).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(order.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");

        second.setStatus("APPROVED");

        assertThat(service().recompute(order.getId())).isEqualTo("ACCEPTED");
        assertThat(order.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectedAndCancelledActsDoNotContributeToCoverage() {
        ServiceOrder order = order(BigDecimal.valueOf(100));
        ServiceAcceptanceAct approved = act(order, "APPROVED", BigDecimal.valueOf(40));
        ServiceAcceptanceAct rejected = act(order, "REJECTED", BigDecimal.valueOf(30));
        ServiceAcceptanceAct cancelled = act(order, "CANCELLED", BigDecimal.valueOf(30));
        stub(order, List.of(approved, rejected, cancelled));

        assertThat(service().recompute(order.getId())).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(order.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");
    }

    @Test
    void rejectionAndRollbackRecomputeAcceptedOrderFromCurrentActiveActs() {
        ServiceOrder order = order(BigDecimal.valueOf(100));
        ServiceAcceptanceAct act = act(order, "APPROVED", BigDecimal.valueOf(100));
        stub(order, List.of(act));
        assertThat(service().recompute(order.getId())).isEqualTo("ACCEPTED");

        act.setStatus("REJECTED");
        assertThat(service().recompute(order.getId())).isEqualTo("ACTIVE");

        act.setStatus("DRAFT");
        assertThat(service().recompute(order.getId())).isEqualTo("ACT_PENDING");
    }

    private void stub(ServiceOrder order, List<ServiceAcceptanceAct> acts) {
        when(orderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(actRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(acts);
        when(orderRepository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ServiceOrderAcceptanceLifecycleService service() {
        return new ServiceOrderAcceptanceLifecycleService(actRepository, orderRepository);
    }

    private ServiceOrder order(BigDecimal totalAmount) {
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-LIFECYCLE");
        order.setName("Lifecycle order");
        order.setStatus("ACTIVE");
        order.setTotalAmount(totalAmount);
        return order;
    }

    private ServiceAcceptanceAct act(ServiceOrder order, String status, BigDecimal amount) {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId(order.getTenantId());
        act.setCompanyId(order.getCompanyId());
        act.setServiceOrderId(order.getId());
        act.setCode("ACT-" + UUID.randomUUID().toString().substring(0, 8));
        act.setName("Lifecycle act");
        act.setStatus(status);
        act.setAmount(amount);
        return act;
    }
}
