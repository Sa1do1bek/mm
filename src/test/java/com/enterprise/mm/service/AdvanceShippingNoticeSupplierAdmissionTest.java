package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.mapper.AdvanceShippingNoticeItemMapper;
import com.enterprise.mm.mapper.AdvanceShippingNoticeMapper;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdvanceShippingNoticeSupplierAdmissionTest {
    @Mock private AdvanceShippingNoticeRepository repository;
    @Mock private AdvanceShippingNoticeMapper mapper;
    @Mock private AuditService auditService;
    @Mock private AdvanceShippingNoticeItemRepository itemRepository;
    @Mock private AdvanceShippingNoticeItemMapper itemMapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private SupplierEligibilityService supplierEligibilityService;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private MaterialRepository materialRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void submitRequiresSupplierEligibilityBeforeShipmentIsAccepted() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID asnId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setSupplierId(supplierId);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setSupplierId(supplierId);
        po.setStatus(PurchaseOrderStatus.SENT.name());
        PurchaseOrderItem poItem = new PurchaseOrderItem();
        poItem.setId(purchaseOrderItemId);
        poItem.setTenantId("default");
        poItem.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        poItem.setPurchaseOrderId(purchaseOrderId);
        poItem.setMaterialId(materialId);
        poItem.setLineType(ProcurementLineType.MATERIAL);
        poItem.setQuantity(BigDecimal.TEN);
        AdvanceShippingNoticeItem asnItem = new AdvanceShippingNoticeItem();
        asnItem.setId(UUID.randomUUID());
        asnItem.setAsnId(asnId);
        asnItem.setPurchaseOrderItemId(purchaseOrderItemId);
        asnItem.setMaterialId(materialId);
        asnItem.setQuantity(BigDecimal.ONE);
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId("default");
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);

        when(repository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(guard.purchaseOrder(purchaseOrderId)).thenReturn(po);
        when(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(purchaseOrderItemId)).thenReturn(Optional.of(poItem));
        when(itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId
        )).thenReturn(List.of(asnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId))
            .thenReturn(Optional.of(material));
        when(repository.save(asn)).thenReturn(asn);

        service().submit(asnId);

        verify(supplierEligibilityService)
            .requireExistingObligationAllowed(supplierId, "ADVANCE_SHIPPING_NOTICE_SUBMISSION");
        verifyNoInteractions(supplierAccreditationService);
    }

    @Test
    void directAsnReceiptCannotBypassPurchaseOrderReceiptOrchestration() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID asnId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setSupplierId(supplierId);
        asn.setStatus(AdvanceShippingNoticeStatus.CONFIRMED.name());

        when(repository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        assertThatThrownBy(() -> service().markReceived(asnId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessage("ASN receipt must be posted through purchase order receipt orchestration");

        verifyNoInteractions(supplierAccreditationService);
    }

    private AdvanceShippingNoticeService service() {
        return new AdvanceShippingNoticeService(
            repository,
            mapper,
            auditService,
            itemRepository,
            itemMapper,
            guard,
            materialCatalogGuard,
            integrationEventService,
            roleDataScopeService,
            supplierAccreditationService,
            supplierEligibilityService,
            purchaseOrderItemRepository,
            materialRepository
        );
    }
}
