package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceOrderLinkServiceTest {
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private AuditService auditService;
    @InjectMocks private PurchaseOrderServiceOrderLinkService service;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void ensureForServicePurchaseOrderCreatesLinkedServiceOrder() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID supplierId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(RequestType.SERVICE, supplierId);
        purchaseOrder.setContractId(contractId);
        purchaseOrder.setCode("PO-SVC-001");
        purchaseOrder.setName("Monthly maintenance");
        purchaseOrder.setTotalAmount(new BigDecimal("300000"));
        purchaseOrder.setCurrencyCode("uzs");
        UUID serviceRequestId = UUID.randomUUID();
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setLineType(ProcurementLineType.SERVICE);
        line.setServiceRequestId(serviceRequestId);
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrder.getId(), supplierId
        )).thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrder.getId()))
            .thenReturn(List.of(line));
        when(serviceOrderRepository.save(any(ServiceOrder.class))).thenAnswer(invocation -> {
            ServiceOrder order = invocation.getArgument(0);
            order.setId(UUID.randomUUID());
            return order;
        });

        Optional<ServiceOrder> result = service.ensureForPurchaseOrder(purchaseOrder, "TEST_CREATE");

        assertThat(result).isPresent();
        ArgumentCaptor<ServiceOrder> captor = ArgumentCaptor.forClass(ServiceOrder.class);
        verify(serviceOrderRepository).save(captor.capture());
        ServiceOrder saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo("default");
        assertThat(saved.getCompanyId()).isEqualTo(CompanyContext.DEFAULT_COMPANY_ID);
        assertThat(saved.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());
        assertThat(saved.getSupplierId()).isEqualTo(supplierId);
        assertThat(saved.getContractId()).isEqualTo(contractId);
        assertThat(saved.getServiceRequestId()).isEqualTo(serviceRequestId);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("300000");
        assertThat(saved.getCurrencyCode()).isEqualTo("UZS");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getServiceType()).isEqualTo("SERVICE");
        assertThat(saved.getCode()).startsWith("SO-PO-SVC-001");
        assertThat(saved.getName()).isNotBlank();
        verify(auditService).log("ServiceOrder", result.get().getId(), "TEST_CREATE", saved.getCode(), null, result.get());
    }

    @Test
    void ensureForServicePurchaseOrderReusesExistingActiveOrder() {
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(RequestType.SERVICE, supplierId);
        ServiceOrder existing = new ServiceOrder();
        existing.setId(UUID.randomUUID());
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrder.getId(), supplierId
        )).thenReturn(List.of(existing));

        Optional<ServiceOrder> result = service.ensureForPurchaseOrder(purchaseOrder, "TEST_REUSE");

        assertThat(result).containsSame(existing);
        verify(serviceOrderRepository, never()).save(any(ServiceOrder.class));
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ensureForGoodsPurchaseOrderDoesNotCreateServiceOrder() {
        PurchaseOrder purchaseOrder = purchaseOrder(RequestType.GOODS, UUID.randomUUID());

        Optional<ServiceOrder> result = service.ensureForPurchaseOrder(purchaseOrder, "TEST_GOODS");

        assertThat(result).isEmpty();
        verify(serviceOrderRepository, never()).save(any(ServiceOrder.class));
    }

    private PurchaseOrder purchaseOrder(RequestType requestType, UUID supplierId) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(UUID.randomUUID());
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(requestType);
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder.setTotalAmount(BigDecimal.TEN);
        return purchaseOrder;
    }
}
