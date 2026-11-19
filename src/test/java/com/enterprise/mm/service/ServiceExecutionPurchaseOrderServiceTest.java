package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.util.List;
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
class ServiceExecutionPurchaseOrderServiceTest {
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private ServiceExecutionRepository serviceExecutionRepository;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private ServiceExecutionService serviceExecutionService;
    @InjectMocks private ServiceExecutionPurchaseOrderService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createFromPurchaseOrderDoesNotFallBackToLineageErrorWhenApprovedActExists() {
        PurchaseOrder order = servicePurchaseOrder();
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setStatus(ServiceAcceptanceActStatus.APPROVED.name());
        when(purchaseOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-test", order.getId()))
            .thenReturn(Optional.of(order));
        when(serviceAcceptanceActRepository.findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            "tenant-test",
            order.getId(),
            List.of(ServiceAcceptanceActStatus.APPROVED.name(), ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(), ServiceAcceptanceActStatus.PAID.name())
        )).thenReturn(List.of(act));

        assertThatThrownBy(() -> service.createFromPurchaseOrder(order.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already completed");
        verify(purchaseOrderItemRepository, never()).findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(any(), any());
    }

    @Test
    void createFromPurchaseOrderReportsMissingServiceRequestLineageBeforeCreatingExecution() {
        PurchaseOrder order = servicePurchaseOrder();
        PurchaseOrderItem item = servicePoItem(order.getId(), null);
        when(purchaseOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-test", order.getId()))
            .thenReturn(Optional.of(order));
        when(serviceAcceptanceActRepository.findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            "tenant-test",
            order.getId(),
            List.of(ServiceAcceptanceActStatus.APPROVED.name(), ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(), ServiceAcceptanceActStatus.PAID.name())
        )).thenReturn(List.of());
        when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc("tenant-test", order.getId()))
            .thenReturn(List.of());
        when(contractPaymentRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc("tenant-test", order.getId()))
            .thenReturn(List.of());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-test", order.getId()))
            .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.createFromPurchaseOrder(order.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("missing serviceRequestId lineage");
        verify(serviceExecutionService, never()).create(any());
    }

    @Test
    void repairPurchaseOrderLineageRestoresServiceRequestIdFromPurchaseRequestItemAllocation() {
        PurchaseOrder order = servicePurchaseOrder();
        PurchaseOrderItem item = servicePoItem(order.getId(), null);
        UUID requestItemId = UUID.randomUUID();
        UUID serviceRequestId = UUID.randomUUID();
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setPurchaseOrderItemId(item.getId());
        allocation.setPurchaseRequestItemId(requestItemId);
        PurchaseRequestItem requestItem = new PurchaseRequestItem();
        requestItem.setId(requestItemId);
        requestItem.setTenantId("tenant-test");
        requestItem.setCompanyId(companyId);
        requestItem.setServiceRequestId(serviceRequestId);
        when(purchaseOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-test", order.getId()))
            .thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("tenant-test", order.getId()))
            .thenReturn(List.of(item));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            "tenant-test", companyId, List.of(item.getId())
        )).thenReturn(List.of(allocation));
        when(purchaseRequestItemRepository.findAllById(List.of(requestItemId))).thenReturn(List.of(requestItem));

        int repaired = service.repairPurchaseOrderLineage(order.getId());

        assertThat(repaired).isEqualTo(1);
        assertThat(item.getServiceRequestId()).isEqualTo(serviceRequestId);
        verify(purchaseOrderItemRepository).save(item);
    }

    private PurchaseOrder servicePurchaseOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("tenant-test");
        order.setCompanyId(companyId);
        order.setSupplierId(UUID.randomUUID());
        order.setRequestType(RequestType.SERVICE);
        order.setStatus("SENT");
        return order;
    }

    private PurchaseOrderItem servicePoItem(UUID purchaseOrderId, UUID serviceRequestId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("tenant-test");
        item.setCompanyId(companyId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.SERVICE);
        item.setServiceRequestId(serviceRequestId);
        return item;
    }
}
