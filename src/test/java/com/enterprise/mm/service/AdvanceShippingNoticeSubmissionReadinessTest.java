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
import com.enterprise.mm.error.BusinessException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AdvanceShippingNoticeSubmissionReadinessTest {
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
    @InjectMocks private AdvanceShippingNoticeService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void submitRequiresLinkedPurchaseOrder() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        fixture.asn().setPurchaseOrderId(null);

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN must be linked to a purchase order before submission");
    }

    @Test
    void submitRejectsPurchaseOrderThatIsNotReceiptEligible() {
        Fixture fixture = fixture(PurchaseOrderStatus.DRAFT);

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN purchase order must be SENT or PARTIALLY_RECEIVED");
    }

    @Test
    void submitRejectsItemFromAnotherPurchaseOrder() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        fixture.poItem().setPurchaseOrderId(UUID.randomUUID());

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item purchase order line does not belong to ASN purchase order");
    }

    @Test
    void submitRejectsMaterialThatDoesNotMatchPurchaseOrderLine() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        fixture.asnItem().setMaterialId(UUID.randomUUID());

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item material must match purchase order line material");
    }

    @Test
    void submitRejectsServicePurchaseOrderLine() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        fixture.poItem().setLineType(ProcurementLineType.SERVICE);

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item must reference a MATERIAL purchase order line");
    }

    @Test
    void submitRejectsNonPositiveItemQuantity() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        fixture.asnItem().setQuantity(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item quantity must be greater than zero");
    }

    @Test
    void submitRejectsCumulativeQuantityAboveOpenPurchaseOrderQuantity() {
        Fixture fixture = fixture(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        fixture.poItem().setQuantity(BigDecimal.TEN);
        fixture.poItem().setReceivedQuantity(BigDecimal.valueOf(3));
        fixture.asnItem().setQuantity(BigDecimal.valueOf(4));
        AdvanceShippingNoticeItem second = asnItem(
            fixture.asn().getId(),
            fixture.poItem().getId(),
            fixture.poItem().getMaterialId(),
            BigDecimal.valueOf(4)
        );
        when(itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, fixture.asn().getId()
        )).thenReturn(List.of(fixture.asnItem(), second));

        assertThatThrownBy(() -> service.submit(fixture.asn().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN item quantity exceeds open purchase order line quantity");
    }

    @Test
    void submitPassesMaterialGroupsToSupplierAccreditation() {
        Fixture fixture = fixture(PurchaseOrderStatus.SENT);
        UUID materialGroupId = UUID.randomUUID();
        Material material = new Material();
        material.setId(fixture.poItem().getMaterialId());
        material.setTenantId("default");
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", material.getId()))
            .thenReturn(Optional.of(material));

        service.submit(fixture.asn().getId());

        verify(supplierEligibilityService).requireExistingObligationAllowed(
            fixture.asn().getSupplierId(),
            "ADVANCE_SHIPPING_NOTICE_SUBMISSION"
        );
        verifyNoInteractions(supplierAccreditationService);
    }

    private Fixture fixture(PurchaseOrderStatus purchaseOrderStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setTenantId("default");
        po.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        po.setSupplierId(supplierId);
        po.setStatus(purchaseOrderStatus.name());
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(UUID.randomUUID());
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setSupplierId(supplierId);
        asn.setPurchaseOrderId(po.getId());
        asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
        asn.setAsnNumber("ASN-1");
        PurchaseOrderItem poItem = poItem(po.getId());
        AdvanceShippingNoticeItem asnItem = asnItem(
            asn.getId(),
            poItem.getId(),
            poItem.getMaterialId(),
            BigDecimal.valueOf(5)
        );
        when(repository.findByIdAndIsDeletedFalse(asn.getId())).thenReturn(Optional.of(asn));
        when(repository.save(asn)).thenReturn(asn);
        when(guard.purchaseOrder(po.getId())).thenReturn(po);
        when(itemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asn.getId()
        )).thenReturn(List.of(asnItem));
        when(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(poItem.getId())).thenReturn(Optional.of(poItem));
        Material material = new Material();
        material.setId(poItem.getMaterialId());
        material.setTenantId("default");
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(UUID.randomUUID());
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", poItem.getMaterialId()))
            .thenReturn(Optional.of(material));
        return new Fixture(asn, po, asnItem, poItem);
    }

    private PurchaseOrderItem poItem(UUID purchaseOrderId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setMaterialId(UUID.randomUUID());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        return item;
    }

    private AdvanceShippingNoticeItem asnItem(UUID asnId, UUID poItemId, UUID materialId, BigDecimal quantity) {
        AdvanceShippingNoticeItem item = new AdvanceShippingNoticeItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setAsnId(asnId);
        item.setPurchaseOrderItemId(poItemId);
        item.setMaterialId(materialId);
        item.setQuantity(quantity);
        return item;
    }

    private record Fixture(
        AdvanceShippingNotice asn,
        PurchaseOrder po,
        AdvanceShippingNoticeItem asnItem,
        PurchaseOrderItem poItem
    ) {
    }
}
