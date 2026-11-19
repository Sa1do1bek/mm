package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierUnblockRequestDto;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierUnblockRequestRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SupplierQualificationServiceSafetyTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void unblockRequestCannotAttachAnotherSuppliersPurchaseOrder() {
        UUID companyId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID otherSupplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId("tenant-a");
        supplier.setCompanyId(companyId);
        supplier.setStatus("BLOCKED");
        supplier.setBlockedReason("Manual block");
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setTenantId("tenant-a");
        po.setCompanyId(companyId);
        po.setSupplierId(otherSupplierId);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(
            anyString(), eq(supplierId), anyString(), any(),
            eq(com.enterprise.mm.payload.SupplierUnblockResponse.class), any()))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(5)).get());
        SupplierQualificationService service = new SupplierQualificationService(
            supplierRepository,
            mock(SupplierAccreditationApplicationRepository.class),
            mock(SupplierAccreditationDecisionRepository.class),
            mock(SupplierUnblockRequestRepository.class),
            purchaseOrderRepository,
            mock(UserRepository.class),
            mock(AuditService.class),
            idempotencyService,
            mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class),
            mock(SupplierAdmissionService.class));

        assertThatThrownBy(() -> service.requestUnblock(supplierId, new SupplierUnblockRequestDto(
            "Resolve expired evidence", purchaseOrderId), "cross-supplier-po"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Purchase order not found");
    }
}
