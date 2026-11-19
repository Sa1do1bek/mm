package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ServiceOrderMapper;
import com.enterprise.mm.payload.ServiceOrderCreateRequest;
import com.enterprise.mm.payload.ServiceOrderUpdateRequest;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
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
class ServiceOrderSupplierAccreditationTest {

    @Mock private ServiceOrderRepository orderRepository;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private ServiceWorkflowAccessService accessService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierAccreditationService accreditationService;

    private ServiceOrderService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ServiceOrderService(
            orderRepository,
            new ServiceOrderMapper(),
            auditService,
            roleDataScopeService,
            requestRepository,
            accessService,
            purchaseOrderRepository,
            accreditationService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void directServiceOrderRequiresServiceAccreditation() {
        UUID supplierId = UUID.randomUUID();
        ServiceOrderCreateRequest request = createRequest(supplierId, "MAINTENANCE");
        doThrow(new BusinessException("Supplier service accreditation is required"))
            .when(accreditationService)
            .assertSupplierEligibleForDemand(
                supplierId, AccreditationDemand.services(), "service order creation");

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("service accreditation");
    }

    @Test
    void directWorksOrderUsesWorksAccreditationInsteadOfServiceAccreditation() {
        UUID supplierId = UUID.randomUUID();
        when(orderRepository.save(any(ServiceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(createRequest(supplierId, "WORKS"));

        verify(accreditationService).assertSupplierEligibleForDemand(
            supplierId, AccreditationDemand.works(), "service order creation");
    }

    @Test
    void draftOrderUpdateIsBlockedBeforeUnaccreditedTermsAreMutated() {
        UUID supplierId = UUID.randomUUID();
        ServiceOrder order = serviceOrder(supplierId, "SERVICE");
        ServiceOrderUpdateRequest request = updateRequest(order);
        request.setTotalAmount(BigDecimal.valueOf(999));
        when(orderRepository.findByIdAndIsDeletedFalse(order.getId())).thenReturn(Optional.of(order));
        when(accessService.canAccessServiceOrder(order, null)).thenReturn(true);
        doThrow(new BusinessException("Supplier service accreditation is required"))
            .when(accreditationService)
            .assertSupplierEligibleForDemand(
                supplierId, AccreditationDemand.services(), "service order update");

        assertThatThrownBy(() -> service.update(order.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("service accreditation");
        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    private ServiceOrderCreateRequest createRequest(UUID supplierId, String serviceType) {
        ServiceOrderCreateRequest request = new ServiceOrderCreateRequest();
        request.setCode("SO-" + shortId());
        request.setName("Accredited service order");
        request.setSupplierId(supplierId);
        request.setServiceType(serviceType);
        request.setTotalAmount(BigDecimal.valueOf(100));
        request.setCurrencyCode("UZS");
        return request;
    }

    private ServiceOrder serviceOrder(UUID supplierId, String serviceType) {
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-" + shortId());
        order.setName("Draft service order");
        order.setStatus("DRAFT");
        order.setSupplierId(supplierId);
        order.setServiceType(serviceType);
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setCurrencyCode("UZS");
        return order;
    }

    private ServiceOrderUpdateRequest updateRequest(ServiceOrder order) {
        ServiceOrderUpdateRequest request = new ServiceOrderUpdateRequest();
        request.setCode(order.getCode());
        request.setName(order.getName());
        request.setServiceRequestId(order.getServiceRequestId());
        request.setSupplierId(order.getSupplierId());
        request.setContractId(order.getContractId());
        request.setPurchaseOrderId(order.getPurchaseOrderId());
        request.setServiceType(order.getServiceType());
        request.setTotalAmount(order.getTotalAmount());
        request.setCurrencyCode(order.getCurrencyCode());
        return request;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }
}
