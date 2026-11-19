package com.enterprise.mm.warehouse.goodsincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.IntegrationBusinessDocumentResolver;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.warehouse.goodsincome.repository.GoodsIncomeQueryRepository;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class GoodsIncomeContextServiceTest {
    @org.mockito.Mock com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    @Mock private GoodsIncomeQueryRepository queryRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private AdvanceShippingNoticeBatchRepository asnBatchRepository;
    @Mock private AdvanceShippingNoticeSerialRepository asnSerialRepository;
    @Mock private SupplierAccreditationApplicationRepository accreditationRepository;
    @Mock private SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    @Mock private SupplierAccreditationService accreditationService;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private IntegrationEventRepository integrationEventRepository;
    @Mock private IntegrationBusinessDocumentResolver documentResolver;
    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private GoodsReceiptLineRepository goodsReceiptLineRepository;
    @InjectMocks private GoodsIncomeWorkspaceService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("goods-income-context-test");
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "warehouse.operator",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextContainsReadableDocumentAndOperationalEvidenceCollections() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem item = item(poId, materialGroupId);
        AdvanceShippingNotice asn = asn(poId, supplierId);
        SupplierAccreditationApplication accreditation = accreditation(supplierId, materialGroupId);
        SupplierAccreditationDecision decision = decision(accreditation.getId(), materialGroupId);
        Attachment evidence = evidence(poId);
        StockMovement movement = movement(poId, item.getMaterialId());
        AuditLog audit = audit(poId);
        IntegrationEvent integration = integration(poId);

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(item));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationRepository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            anyString(), any(UUID.class), any(UUID.class), any(Set.class)
        )).thenReturn(List.of(accreditation));
        when(accreditationDecisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, accreditation.getId()
        )).thenReturn(List.of(decision));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "PURCHASEORDER", poId
        )).thenReturn(List.of(evidence));
        when(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), "PurchaseOrder", poId
        )).thenReturn(List.of(movement));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, "PurchaseOrder", poId
        )).thenReturn(List.of(audit));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(integration));
        when(documentResolver.resolve(integration)).thenReturn(new BusinessDocumentReference(
            "Purchase Order", poId, "PO-GI-15", "Bearing supply", "purchase-orders", "/app/purchase-orders/" + poId
        ));

        var context = service.context(poId);

        assertThat(context.header().supplier().displayName()).isEqualTo("Bearing Supplier LLC");
        assertThat(context.lines()).singleElement().satisfies(line -> {
            assertThat(line.material().code()).isEqualTo("MAT-BEARING");
            assertThat(line.material().displayName()).isEqualTo("Industrial bearing");
            assertThat(line.materialGroup().displayName()).isEqualTo("Mechanical spares");
            assertThat(line.acceptedQuantity()).isEqualByComparingTo("0");
            assertThat(line.openQuantity()).isEqualByComparingTo("8");
            assertThat(line.previouslyReceivedQuantity()).isEqualByComparingTo("2");
            assertThat(line.shortageQuantity()).isEqualByComparingTo("0");
            assertThat(line.excessQuantity()).isEqualByComparingTo("0");
        });
        assertThat(context.advanceShippingNotices()).singleElement().satisfies(ref ->
        {
            assertThat(ref.displayName()).contains("ASN-2026-15");
            assertThat(ref.receiptEligible()).isTrue();
            assertThat(ref.receiptBlockedReason()).isNull();
        });
        assertThat(context.accreditation().eligible()).isTrue();
        assertThat(context.evidence()).hasSize(1);
        assertThat(context.stockMovements()).hasSize(1);
        assertThat(context.auditTimeline()).hasSize(1);
        assertThat(context.integrationImpact()).singleElement().satisfies(impact -> {
            // UX-LIVE-03: related business document + recovery status must be surfaced so the
            // FE can point the user back at the document instead of a bare integration event id.
            assertThat(impact.businessDocumentType()).isEqualTo("Purchase Order");
            assertThat(impact.businessDocumentCode()).isEqualTo("PO-GI-15");
            assertThat(impact.identityAvailable()).isTrue();
            assertThat(impact.recoveryStatus()).isEqualTo("RESOLVED");
        });
        assertThat(context.blockers()).isEmpty();
        assertThat(context.allowedActions()).singleElement().satisfies(action -> {
            assertThat(action.code()).isEqualTo("RECEIVE");
            assertThat(action.enabled()).isTrue();
            assertThat(action.requiredRoles()).contains("WAREHOUSE_OPERATOR");
            assertThat(action.requiredFields()).contains("asnId");
            assertThat(action.requiredEvidence()).contains("PURCHASE_ORDER_ATTACHMENT_FOR_EXCEPTIONS");
            assertThat(action.idempotencyRequired()).isTrue();
        });
    }

    @Test
    void contextBlocksReceiptUntilAsnIsConfirmed() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem item = item(poId, materialGroupId);
        AdvanceShippingNotice asn = asn(poId, supplierId);
        asn.setStatus("SENT");

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(item));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));

        var context = service.context(poId);

        assertThat(context.advanceShippingNotices()).singleElement().satisfies(ref -> {
            assertThat(ref.receiptEligible()).isFalse();
            assertThat(ref.receiptBlockedReason()).isEqualTo("ASN must be CONFIRMED before goods receipt");
        });
        assertThat(context.blockers()).anySatisfy(blocker -> {
            assertThat(blocker.code()).isEqualTo("ASN_CONFIRMATION_REQUIRED");
            assertThat(blocker.resolutionAction()).isEqualTo("CONFIRM_DELIVERY");
        });
        assertThat(context.allowedActions()).singleElement().satisfies(action -> {
            assertThat(action.enabled()).isFalse();
            assertThat(action.disabledReason()).isEqualTo("Confirmed ASN is required before goods receipt");
        });
    }

    @Test
    void queueTreatsMissingLineUnitAsDataQualityBlockerInsteadOfCommonUom() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem item = item(poId, materialGroupId);
        item.setUnit(null);
        AdvanceShippingNotice asn = asn(poId, supplierId);

        when(queryRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(po)));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(item));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));

        var queue = service.queue(null, PageRequest.of(0, 20));

        assertThat(queue.items()).singleElement().satisfies(row -> {
            assertThat(row.openQuantity()).isNull();
            assertThat(row.unit()).isNull();
            assertThat(row.openQuantityUom()).isNull();
            assertThat(row.mixedUom()).isFalse();
            assertThat(row.blockerCode()).isEqualTo("UNIT_OF_MEASURE_REQUIRED");
            assertThat(row.nextAction()).isEqualTo("RESOLVE_UNIT_OF_MEASURE");
            assertThat(row.ownerRole()).isEqualTo("PROCUREMENT_MANAGER");
        });
    }

    @Test
    void queueIncludesUnitWhenAllOrderLinesUseSameUnit() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem firstItem = item(poId, materialGroupId);
        PurchaseOrderItem secondItem = item(poId, materialGroupId);
        AdvanceShippingNotice asn = asn(poId, supplierId);

        when(queryRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(po)));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(firstItem, secondItem));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));

        var queue = service.queue(null, PageRequest.of(0, 20));

        assertThat(queue.items()).singleElement().satisfies(row -> {
            assertThat(row.openQuantity()).isEqualByComparingTo("16");
            assertThat(row.unit()).isEqualTo("pcs");
            assertThat(row.openQuantityUom()).isEqualTo("pcs");
            assertThat(row.mixedUom()).isFalse();
        });
    }

    @Test
    void queueReturnsNullUnitWhenOrderLineUnitsDiffer() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem firstItem = item(poId, materialGroupId);
        PurchaseOrderItem secondItem = item(poId, materialGroupId);
        secondItem.setUnit("kg");
        AdvanceShippingNotice asn = asn(poId, supplierId);

        when(queryRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(po)));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(firstItem, secondItem));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));

        var queue = service.queue(null, PageRequest.of(0, 20));

        assertThat(queue.items()).singleElement().satisfies(row -> {
            assertThat(row.openQuantity()).isNull();
            assertThat(row.unit()).isNull();
            assertThat(row.openQuantityUom()).isNull();
            assertThat(row.mixedUom()).isTrue();
        });
    }

    @Test
    void contextSurfacesFailedIntegrationEventAsNeedingRecoveryWithoutDocumentIdentity() {
        // UX-LIVE-03: a FAILED event whose business document identity could not be resolved
        // must still surface a NEEDS_RECOVERY status instead of silently dropping the signal.
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        Supplier supplier = supplier(supplierId);
        PurchaseOrderItem item = item(poId, materialGroupId);
        AdvanceShippingNotice asn = asn(poId, supplierId);
        IntegrationEvent failedEvent = integration(poId);
        failedEvent.setStatus("FAILED");
        failedEvent.setLastError("Downstream ERP timeout");

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .thenReturn(List.of(item));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId))
            .thenReturn(Optional.of(supplier));
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(asn));
        when(accreditationService.effectiveEligibility(
            eq(supplierId), anyList(), any(LocalDate.class), anyString()
        )).thenReturn(effectiveEligibility(supplierId, materialGroupId, true, "ELIGIBLE"));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, poId
        )).thenReturn(List.of(failedEvent));
        when(documentResolver.resolve(failedEvent)).thenReturn(new BusinessDocumentReference(
            "Purchase Order", poId, null, "Document identity unavailable", null, null, null, false
        ));

        var context = service.context(poId);

        assertThat(context.integrationImpact()).singleElement().satisfies(impact -> {
            assertThat(impact.status()).isEqualTo("FAILED");
            assertThat(impact.businessDocumentType()).isEqualTo("Purchase Order");
            assertThat(impact.businessDocumentCode()).isNull();
            assertThat(impact.identityAvailable()).isFalse();
            assertThat(impact.recoveryStatus()).isEqualTo("NEEDS_RECOVERY");
        });
    }

    @Test
    void contextRejectsMissingPurchaseOrderWithTypedNotFoundKey() {
        UUID poId = UUID.randomUUID();
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.context(poId))
            .isInstanceOf(NotFoundException.class)
            .satisfies(exception -> assertThat(((NotFoundException) exception).getMessageKey())
                .isEqualTo("goodsIncome.po.notFound"));
    }

    @Test
    void contextRejectsNonGoodsPurchaseOrderWithTypedBusinessKey() {
        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder po = purchaseOrder(poId, supplierId);
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.context(poId))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getMessageKey())
                .isEqualTo("goodsIncome.po.notGoods"));
    }

    private PurchaseOrder purchaseOrder(UUID poId, UUID supplierId) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(poId);
        po.setTenantId(TenantContext.currentTenant());
        po.setCompanyId(companyId);
        po.setCode("PO-GI-15");
        po.setName("Bearing supply");
        po.setStatus(PurchaseOrderStatus.SENT.name());
        po.setSupplierId(supplierId);
        po.setRequestType(RequestType.GOODS);
        po.setExpectedDeliveryDate(LocalDate.now());
        po.setCurrencyCode("UZS");
        po.setTotalAmount(BigDecimal.valueOf(840));
        po.setVersion(5L);
        return po;
    }

    private Supplier supplier(UUID id) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-BEARING");
        supplier.setName("Bearing Supplier LLC");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private PurchaseOrderItem item(UUID poId, UUID materialGroupId) {
        MaterialGroup group = new MaterialGroup();
        group.setId(materialGroupId);
        group.setCode("MG-MECH");
        group.setName("Mechanical spares");
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("MAT-BEARING");
        material.setName("Industrial bearing");
        material.setMaterialGroupId(materialGroupId);
        material.setMaterialGroup(group);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(poId);
        item.setCode("10");
        item.setName("Bearing line");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(material.getId());
        item.setMaterial(material);
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.valueOf(2));
        item.setRejectedQuantity(BigDecimal.ONE);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnit("pcs");
        item.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        return item;
    }

    private AdvanceShippingNotice asn(UUID poId, UUID supplierId) {
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(UUID.randomUUID());
        asn.setTenantId(TenantContext.currentTenant());
        asn.setCompanyId(companyId);
        asn.setPurchaseOrderId(poId);
        asn.setSupplierId(supplierId);
        asn.setAsnNumber("ASN-2026-15");
        asn.setCode("ASN-2026-15");
        asn.setName("ASN-2026-15 / truck 01");
        asn.setStatus("CONFIRMED");
        asn.setExpectedArrivalDate(LocalDate.now());
        return asn;
    }

    private SupplierAccreditationApplication accreditation(UUID supplierId, UUID materialGroupId) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setSupplierId(supplierId);
        application.setCode("ACC-2026-15");
        application.setName("Goods accreditation");
        application.setStatus("APPROVED");
        application.setRequestedScope("Mechanical spares");
        application.setRequestedMaterialGroupIds(List.of(materialGroupId));
        application.setValidTo(LocalDate.now().plusMonths(6));
        return application;
    }

    private SupplierAccreditationDecision decision(UUID applicationId, UUID materialGroupId) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setId(UUID.randomUUID());
        decision.setApplicationId(applicationId);
        decision.setDecision("APPROVED");
        decision.setAllowedMaterialGroupIds(List.of(materialGroupId));
        decision.setValidTo(LocalDate.now().plusMonths(6));
        return decision;
    }

    private Attachment evidence(UUID poId) {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setCode("ATT-GI-1");
        attachment.setName("Packing list.pdf");
        attachment.setEntityType("PURCHASEORDER");
        attachment.setEntityId(poId);
        attachment.setAttachmentType("RECEIPT_EVIDENCE");
        attachment.setOriginalFileName("packing-list.pdf");
        attachment.setContentType("application/pdf");
        attachment.setUploadedAt(LocalDateTime.now());
        return attachment;
    }

    private StockMovement movement(UUID poId, UUID materialId) {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.randomUUID());
        movement.setTenantId(TenantContext.currentTenant());
        movement.setCompanyId(companyId);
        movement.setCode("GR-PO-GI-15");
        movement.setMaterialId(materialId);
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(BigDecimal.valueOf(2));
        movement.setMovementDate(LocalDate.now());
        movement.setReferenceType("PurchaseOrder");
        movement.setReferenceId(poId);
        movement.setStatus("POSTED");
        return movement;
    }

    private AuditLog audit(UUID poId) {
        AuditLog audit = new AuditLog();
        audit.setId(UUID.randomUUID());
        audit.setEntityType("PurchaseOrder");
        audit.setEntityId(poId);
        audit.setAction("SEND");
        audit.setUsername("buyer");
        audit.setOccurredAt(LocalDateTime.now());
        return audit;
    }

    private EffectiveSupplierEligibility effectiveEligibility(
        UUID supplierId, UUID materialGroupId, boolean eligible, String overallStatus
    ) {
        MaterialGroupEligibility group = new MaterialGroupEligibility(
            materialGroupId, eligible, eligible ? null : "SUPPLIER_MATERIAL_GROUP_NOT_COVERED",
            eligible ? null : "Supplier accreditation does not cover the material group",
            null, null, null, null, null
        );
        return new EffectiveSupplierEligibility(
            supplierId, LocalDate.now(), overallStatus, eligible, List.of(group), List.of(), null
        );
    }

    private IntegrationEvent integration(UUID poId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setEventType("MM_PO_SENT");
        event.setAggregateType("PurchaseOrder");
        event.setAggregateId(poId);
        event.setStatus("PUBLISHED");
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
