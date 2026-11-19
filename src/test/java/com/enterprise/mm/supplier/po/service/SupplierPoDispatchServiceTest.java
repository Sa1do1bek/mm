package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierPoDispatchServiceTest {
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderRevisionRepository purchaseOrderRevisionRepository;
    @Mock private SupplierPoDispatchRepository dispatchRepository;
    @Mock private SupplierPortalAccountRepository portalAccountRepository;
    @Mock private SupplierPoSnapshotService snapshotService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoDispatchService service;

    private UUID purchaseOrderId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrderId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void reusesExistingDispatchForSamePoVersionWithoutBuildingANewSnapshot() {
        PurchaseOrder po = purchaseOrder(5L);
        SupplierPoDispatch existing = new SupplierPoDispatch();
        existing.setId(UUID.randomUUID());
        existing.setPurchaseOrderVersion(5L);

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndPurchaseOrderVersionAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, 5L
        )).thenReturn(Optional.of(existing));

        SupplierPoDispatchService.DispatchOutcome outcome = service.createOrReuseDispatch(purchaseOrderId);

        assertThat(outcome.newlyCreated()).isFalse();
        assertThat(outcome.dispatch()).isSameAs(existing);
        verify(snapshotService, never()).build(any(), any());
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    void createsNewDispatchAndDemotesPriorCurrentWhenPoVersionChanged() {
        PurchaseOrder po = purchaseOrder(6L);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(BigDecimal.TEN);

        SupplierPoDispatch previousCurrent = new SupplierPoDispatch();
        previousCurrent.setId(UUID.randomUUID());
        previousCurrent.setPurchaseOrderVersion(5L);
        previousCurrent.setCurrent(true);

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndPurchaseOrderVersionAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, 6L
        )).thenReturn(Optional.empty());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.of(previousCurrent));
        when(purchaseOrderRevisionRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByRevisionNumberDesc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
            )).thenReturn(List.of());
        when(portalAccountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, po.getSupplierId()
        )).thenReturn(List.of());
        when(snapshotService.build(po, List.of(item)))
            .thenReturn(new SupplierPoSnapshotService.Snapshot("{}", "hash-6"));
        when(dispatchRepository.save(any(SupplierPoDispatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierPoDispatchService.DispatchOutcome outcome = service.createOrReuseDispatch(purchaseOrderId);

        assertThat(outcome.newlyCreated()).isTrue();
        assertThat(outcome.dispatch().getPurchaseOrderVersion()).isEqualTo(6L);
        assertThat(outcome.dispatch().getSnapshotHash()).isEqualTo("hash-6");
        assertThat(outcome.dispatch().isCurrent()).isTrue();
        assertThat(previousCurrent.isCurrent()).isFalse();

        ArgumentCaptor<SupplierPoDispatch> savedCaptor = ArgumentCaptor.forClass(SupplierPoDispatch.class);
        verify(dispatchRepository, org.mockito.Mockito.times(2)).save(savedCaptor.capture());
    }

    private PurchaseOrder purchaseOrder(Long version) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setTenantId("default");
        po.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        po.setCode("PO-1");
        po.setVersion(version);
        po.setSupplierId(UUID.randomUUID());
        return po;
    }
}
