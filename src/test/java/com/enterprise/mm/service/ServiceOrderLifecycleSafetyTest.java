package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ServiceOrderMapper;
import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.ServiceOrderUpdateRequest;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceOrderLifecycleSafetyTest {
    @Mock private ServiceOrderRepository repository;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceWorkflowAccessService accessService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierAccreditationService supplierAccreditationService;

    private final ServiceOrderMapper mapper = new ServiceOrderMapper();
    private ServiceOrderService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ServiceOrderService(
            repository,
            mapper,
            auditService,
            roleDataScopeService,
            requestRepository,
            accessService,
            purchaseOrderRepository,
            supplierAccreditationService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void updateRejectsAcceptedOrderBeforeMutatingApprovedTerms() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = order("ACCEPTED", requestId);
        ServiceOrderUpdateRequest update = update(requestId);
        update.setTotalAmount(BigDecimal.valueOf(999));
        existing(order, request(requestId));

        assertThatThrownBy(() -> service.update(order.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DRAFT");

        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void updateRejectsServiceRequestReparentingWhileDraft() {
        UUID originalRequestId = UUID.randomUUID();
        UUID replacementRequestId = UUID.randomUUID();
        ServiceOrder order = order("DRAFT", originalRequestId);
        ServiceOrderUpdateRequest update = update(replacementRequestId);
        existing(order, request(originalRequestId));
        lenient().when(requestRepository.findByIdAndIsDeletedFalse(replacementRequestId)).thenReturn(Optional.of(request(replacementRequestId)));

        assertThatThrownBy(() -> service.update(order.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("serviceRequestId");

        assertThat(order.getServiceRequestId()).isEqualTo(originalRequestId);
    }

    @Test
    void updateAllowsDraftFinancialChangesAndPreservesStatus() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = order("DRAFT", requestId);
        ServiceOrderUpdateRequest update = update(requestId);
        update.setTotalAmount(BigDecimal.valueOf(250));
        update.setCurrencyCode("USD");
        existing(order, request(requestId));

        ServiceOrderResponse response = service.update(order.getId(), update);

        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(250));
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
    }

    private void existing(ServiceOrder order, ServiceRequest request) {
        when(repository.findByIdAndIsDeletedFalse(order.getId())).thenReturn(Optional.of(order));
        when(requestRepository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        lenient().when(accessService.canAccessServiceRequest(any(ServiceRequest.class))).thenReturn(true);
        when(accessService.canAccessServiceOrder(order, request)).thenReturn(true);
        lenient().when(repository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ServiceOrder order(String status, UUID requestId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-1");
        order.setName("Service order");
        order.setStatus(status);
        order.setServiceRequestId(requestId);
        order.setSupplierId(UUID.randomUUID());
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setCurrencyCode("UZS");
        return order;
    }

    private ServiceRequest request(UUID id) {
        ServiceRequest request = new ServiceRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setStatus("WAITING_EXECUTION");
        return request;
    }

    private ServiceOrderUpdateRequest update(UUID requestId) {
        ServiceOrderUpdateRequest update = new ServiceOrderUpdateRequest();
        update.setCode("SO-1");
        update.setName("Service order");
        update.setServiceRequestId(requestId);
        update.setSupplierId(UUID.randomUUID());
        update.setTotalAmount(BigDecimal.valueOf(100));
        update.setCurrencyCode("UZS");
        return update;
    }
}
