package com.enterprise.mm.warehouse.goodsincome.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.DataQualityGuards;
import com.enterprise.mm.common.IntegrationRecoveryStatus;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeBatch;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.AdvanceShippingNoticeSerial;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.BusinessDocumentReference;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.service.IntegrationBusinessDocumentResolver;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AccreditationContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AdvanceShippingNoticeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.AuditTimelineItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EntityRef;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.EvidenceItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.ExpectedAsnBatch;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.ExpectedAsnSerial;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeHeader;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeLine;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueFilter;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueResponse;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.IntegrationImpactItem;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.StockMovementContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.ReceiptContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.ReceiptLineContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.WorkspaceAction;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.WorkspaceBlocker;
import com.enterprise.mm.warehouse.goodsincome.repository.GoodsIncomeQueryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoodsIncomeWorkspaceService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private static final Set<String> ELIGIBLE_ACCREDITATION_STATUSES = Set.of("APPROVED", "CONDITIONALLY_APPROVED");
    private static final List<String> RECEIVE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR",
        "WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER"
    );

    private final GoodsIncomeQueryRepository queryRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final AdvanceShippingNoticeItemRepository asnItemRepository;
    private final AdvanceShippingNoticeBatchRepository asnBatchRepository;
    private final AdvanceShippingNoticeSerialRepository asnSerialRepository;
    private final SupplierAccreditationApplicationRepository accreditationRepository;
    private final SupplierAccreditationDecisionRepository accreditationDecisionRepository;
    private final SupplierAccreditationService accreditationService;
    private final AttachmentRepository attachmentRepository;
    private final StockMovementRepository stockMovementRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final AuditLogRepository auditLogRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final IntegrationBusinessDocumentResolver documentResolver;

    @Transactional(readOnly = true)
    public GoodsIncomeQueueResponse queue(GoodsIncomeQueueFilter filter, Pageable pageable) {
        return queue(filter, pageable, false);
    }

    @Transactional(readOnly = true)
    public GoodsIncomeQueueResponse queueForCompany(GoodsIncomeQueueFilter filter, Pageable pageable) {
        return queue(filter, pageable, true);
    }

    private GoodsIncomeQueueResponse queue(
        GoodsIncomeQueueFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        GoodsIncomeQueueFilter normalized = (filter == null
            ? new GoodsIncomeQueueFilter(null, List.of(), null, null)
            : filter).normalized();
        var page = forceCompanyScope
            ? queryRepository.searchForCompany(normalized, pageable)
            : queryRepository.search(normalized, pageable);
        List<GoodsIncomeQueueItem> items = page.getContent().stream().map(this::queueItem).toList();
        return new GoodsIncomeQueueResponse(
            "receiving",
            "goodsIncome.queue.receiving",
            normalized,
            page.getTotalElements(),
            items,
            page.getNumber(),
            page.getSize(),
            page.getTotalPages(),
            LocalDateTime.now(),
            forceCompanyScope ? "COMPANY" : dataScope()
        );
    }

    @Transactional(readOnly = true)
    public GoodsIncomeContext context(UUID purchaseOrderId) {
        return context(purchaseOrderId, null);
    }

    @Transactional(readOnly = true)
    public GoodsIncomeContext context(UUID purchaseOrderId, LocalDate asOfDate) {
        LocalDate eligibilityDate = asOfDate == null ? LocalDate.now() : asOfDate;
        PurchaseOrder po = requirePurchaseOrder(purchaseOrderId);
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getSupplierId()
        ).filter(entity -> Objects.equals(entity.getCompanyId(), po.getCompanyId()))
            .orElseThrow(() -> new BusinessException(
                "Supplier not found for purchase order: " + purchaseOrderId,
                "goodsIncome.supplier.notFound", purchaseOrderId));
        List<PurchaseOrderItem> allItems = purchaseOrderItems(po.getId());
        List<PurchaseOrderItem> items = allItems.stream().filter(this::isTmbMaterialLine).toList();
        List<AdvanceShippingNotice> asns = asns(po);
        String accreditationBlockedReason = accreditationBlockedReason(supplier.getId(), items, eligibilityDate);
        boolean accreditationEligible = accreditationBlockedReason == null;
        List<WorkspaceBlocker> blockers = blockers(po, allItems, items, asns, accreditationBlockedReason);

        return new GoodsIncomeContext(
            header(po, supplier),
            items.stream().map(item -> line(item, accreditationEligible, accreditationBlockedReason)).toList(),
            asns.stream().map(this::asnContext).toList(),
            accreditation(po.getCompanyId(), supplier.getId(), items, accreditationBlockedReason, eligibilityDate),
            blockers,
            List.of(receiveAction(po, items, blockers)),
            evidence(po.getCompanyId(), po.getId()),
            receipts(po.getCompanyId(), po.getId()),
            stockMovements(po.getCompanyId(), po.getId()),
            auditTimeline(po.getCompanyId(), po.getId()),
            integrationImpact(po.getCompanyId(), po.getId()),
            LocalDateTime.now(),
            dataScope()
        );
    }

    private AdvanceShippingNoticeContext asnContext(AdvanceShippingNotice asn) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = asn.getCompanyId();
        List<AdvanceShippingNoticeItem> asnItems = asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(tenantId, companyId, asn.getId());
        List<ExpectedAsnBatch> expectedBatches = asnItems.stream()
            .flatMap(item -> asnBatchRepository
                .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId())
                .stream()
                .map(batch -> expectedBatch(item, batch)))
            .toList();
        List<ExpectedAsnSerial> expectedSerials = asnItems.stream()
            .flatMap(item -> asnSerialRepository
                .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, companyId, item.getId())
                .stream()
                .map(serial -> expectedSerial(item, serial)))
            .toList();
        return new AdvanceShippingNoticeContext(
            asn.getId(),
            asn.getCode(),
            displayName(asn.getName(), asn.getAsnNumber(), asn.getCode()),
            asn.getStatus(),
            asn.getExpectedArrivalDate(),
            asn.getCarrierName(),
            asn.getVehicleNumber(),
            isReceiptEligibleAsn(asn),
            asnReceiptBlockedReason(asn),
            expectedBatches,
            expectedSerials
        );
    }

    private ExpectedAsnBatch expectedBatch(AdvanceShippingNoticeItem item, AdvanceShippingNoticeBatch batch) {
        return new ExpectedAsnBatch(
            item.getId(),
            batch.getBatchNumber(),
            batch.getQuantity(),
            batch.getExpirationDate()
        );
    }

    private ExpectedAsnSerial expectedSerial(AdvanceShippingNoticeItem item, AdvanceShippingNoticeSerial serial) {
        return new ExpectedAsnSerial(
            item.getId(),
            serial.getManufacturerSerialNumber(),
            serial.getBatchNumber()
        );
    }

    private List<ReceiptContext> receipts(UUID companyId, UUID purchaseOrderId) {
        return goodsReceiptRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, purchaseOrderId)
            .stream().map(receipt -> receipt(receipt, companyId)).toList();
    }

    private ReceiptContext receipt(GoodsReceipt receipt, UUID companyId) {
        List<ReceiptLineContext> lines = goodsReceiptLineRepository
            .findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), companyId, receipt.getId())
            .stream().map(line -> new ReceiptLineContext(
                line.getId(),
                line.getPurchaseOrderItemId(),
                line.getMaterialId(),
                line.getUomCode(),
                line.getStorageLocationId(),
                line.getStorageBinId(),
                line.getBatchNumber(),
                nvl(line.getOpenQuantityBefore()),
                nvl(line.getAcceptedQuantity()),
                nvl(line.getRejectedQuantity()),
                nvl(line.getDamagedQuantity()),
                nvl(line.getShortageQuantity()),
                nvl(line.getExcessQuantity()),
                line.getShortageReason(),
                line.getQualityStatus(),
                line.getStockMovementId(),
                line.getStatus()
            ).withUnitShortName(unitDisplay.shortName(line, null, line.getUomCode()))).toList();
        return new ReceiptContext(
            receipt.getId(),
            receipt.getCode(),
            receipt.getStatus(),
            receipt.getWarehouseId(),
            nvl(receipt.getAcceptedQuantity()),
            nvl(receipt.getRejectedQuantity()),
            nvl(receipt.getDamagedQuantity()),
            nvl(receipt.getShortageQuantity()),
            nvl(receipt.getAcceptedAmount()),
            receipt.getCurrencyCode(),
            nvl(receipt.getBaseAcceptedAmount()),
            receipt.getBaseCurrencyCode(),
            receipt.getExchangeRate(),
            receipt.getPostedAt(),
            receipt.getPostedBy(),
            lines
        );
    }

    private GoodsIncomeQueueItem queueItem(PurchaseOrder po) {
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getSupplierId()
        ).filter(entity -> AccessScope.canAccessCompany(entity.getCompanyId()))
            .filter(entity -> !AccessScope.companyScoped(true)
                || Objects.equals(entity.getCompanyId(), po.getCompanyId()))
            .orElse(null);
        List<PurchaseOrderItem> allItems = purchaseOrderItems(po.getId());
        List<PurchaseOrderItem> items = allItems.stream().filter(this::isTmbMaterialLine).toList();
        List<AdvanceShippingNotice> asns = asns(po);
        String blocker = supplier == null
            ? "Supplier data is unavailable"
            : asnRequiredBlockedReason(asns);
        if (blocker == null && supplier != null) {
            blocker = accreditationBlockedReason(supplier.getId(), items, LocalDate.now());
        }
        List<PurchaseOrderItem> openItems = items.stream().filter(item -> openQuantity(item).signum() > 0).toList();
        long openLineCount = openItems.size();
        List<String> orderUoms = items.stream().map(item -> trimToNull(item.getUnit())).distinct().toList();
        String unit = orderUoms.size() == 1 && orderUoms.get(0) != null ? orderUoms.get(0) : null;
        List<String> openUoms = openItems.stream().map(item -> trimToNull(item.getUnit())).distinct().toList();
        boolean missingUom = openUoms.stream().anyMatch(Objects::isNull);
        List<String> knownOpenUoms = openUoms.stream().filter(Objects::nonNull).toList();
        boolean mixedUom = knownOpenUoms.size() > 1;
        boolean aggregateQuantityUnavailable = missingUom || mixedUom;
        BigDecimal openQuantity = aggregateQuantityUnavailable
            ? null
            : openItems.stream().map(this::openQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        String openQuantityUom = aggregateQuantityUnavailable || knownOpenUoms.isEmpty() ? null : knownOpenUoms.get(0);
        boolean overdue = po.getExpectedDeliveryDate() != null && po.getExpectedDeliveryDate().isBefore(LocalDate.now());
        if (blocker == null && missingUom) {
            blocker = "Purchase order line unit of measure is required before goods receipt";
        }
        String blockerCode = blocker == null ? null : blocker.startsWith("Confirmed ASN")
            ? "ASN_CONFIRMATION_REQUIRED"
            : blocker.startsWith("Purchase order line unit of measure")
            ? "UNIT_OF_MEASURE_REQUIRED"
            : "SUPPLIER_ACCREDITATION_BLOCKED";
        return new GoodsIncomeQueueItem(
            ref(po.getId(), po.getCode(), po.getName(), po.getStatus()),
            supplier == null ? null : ref(supplier.getId(), supplier.getCode(), supplierDisplayName(supplier), supplier.getStatus()),
            po.getContract() == null ? null : ref(
                po.getContract().getId(),
                po.getContract().getContractNumber(),
                displayName(po.getContract().getName(), po.getContract().getContractNumber()),
                po.getContract().getStatus()
            ),
            "purchaseOrder.status." + po.getStatus(),
            po.getExpectedDeliveryDate(),
            overdue,
            DataQualityGuards.roundMoney(nvl(po.getTotalAmount()), po.getCurrencyCode()),
            po.getCurrencyCode(),
            openLineCount,
            openQuantity,
            unit,
            openQuantityUom,
            mixedUom,
            blocker == null ? "RECEIVE" : "ASN_CONFIRMATION_REQUIRED".equals(blockerCode) ? "CONFIRM_DELIVERY" : "UNIT_OF_MEASURE_REQUIRED".equals(blockerCode) ? "RESOLVE_UNIT_OF_MEASURE" : "RESOLVE_ACCREDITATION",
            blockerCode,
            blocker,
            blocker == null ? "WAREHOUSE_OPERATOR" : "ASN_CONFIRMATION_REQUIRED".equals(blockerCode) || "UNIT_OF_MEASURE_REQUIRED".equals(blockerCode) ? "PROCUREMENT_MANAGER" : "SUPPLIER_MANAGER",
            po.getExpectedDeliveryDate(),
            po.getVersion()
        ).withUnitShortName(unitDisplay.shortName(po, null, unit))
            .withOpenQuantityUomShortName(unitDisplay.shortName(po, null, openQuantityUom));
    }

    private PurchaseOrder requirePurchaseOrder(UUID id) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .orElseThrow(() -> new NotFoundException(
                "Goods receipt purchase order not found: " + id, "goodsIncome.po.notFound", id));
        if (!AccessScope.canAccessCompany(po.getCompanyId())) {
            throw new BusinessException(
                "Purchase order company is not accessible in the current scope: " + id,
                "goodsIncome.po.companyAccessDenied", id);
        }
        if (po.getRequestType() != RequestType.GOODS) {
            throw new BusinessException(
                "Purchase order is not a GOODS request and is not eligible for goods receipt: " + id,
                "goodsIncome.po.notGoods", id);
        }
        return po;
    }

    private List<PurchaseOrderItem> materialItems(UUID purchaseOrderId) {
        return purchaseOrderItems(purchaseOrderId).stream().filter(this::isTmbMaterialLine).toList();
    }

    private List<PurchaseOrderItem> purchaseOrderItems(UUID purchaseOrderId) {
        return itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        );
    }

    private String accreditationBlockedReason(UUID supplierId, List<PurchaseOrderItem> items, LocalDate asOfDate) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        boolean incompleteScope = items.stream().anyMatch(item ->
            item.getMaterial() == null || item.getMaterial().getMaterialGroupId() == null);
        if (incompleteScope) {
            return "Material group is required for every material line in goods receipt";
        }
        List<UUID> materialGroupIds = items.stream()
            .map(PurchaseOrderItem::getMaterial)
            .map(material -> material.getMaterialGroupId())
            .distinct()
            .sorted()
            .toList();
        EffectiveSupplierEligibility eligibility = accreditationService.effectiveEligibility(
            supplierId, materialGroupIds, asOfDate, "goods receipt");
        if (eligibility.eligibleForRequestedScope()) {
            return null;
        }
        return eligibility.summaryReason() == null
            ? "Supplier accreditation is required for goods receipt"
            : eligibility.summaryReason();
    }

    private GoodsIncomeHeader header(PurchaseOrder po, Supplier supplier) {
        return new GoodsIncomeHeader(
            po.getId(),
            po.getCode(),
            displayName(po.getName(), po.getCode()),
            po.getStatus(),
            po.getVersion(),
            ref(supplier.getId(), supplier.getCode(), supplierDisplayName(supplier), supplier.getStatus()),
            po.getContract() == null ? null : ref(
                po.getContract().getId(),
                po.getContract().getContractNumber(),
                displayName(po.getContract().getName(), po.getContract().getContractNumber()),
                po.getContract().getStatus()
            ),
            po.getOrderDate(),
            po.getExpectedDeliveryDate(),
            po.getRequestType() == null ? null : po.getRequestType().name(),
            DataQualityGuards.roundMoney(nvl(po.getTotalAmount()), po.getCurrencyCode()),
            po.getCurrencyCode()
        );
    }

    private GoodsIncomeLine line(PurchaseOrderItem item, boolean accreditationEligible, String accreditationMessage) {
        var material = item.getMaterial();
        MaterialGroup group = material == null ? null : material.getMaterialGroup();
        BigDecimal physicalReceived = nvl(item.getReceivedQuantity());
        BigDecimal accepted = nvl(item.getAcceptedQuantity());
        BigDecimal rejected = nvl(item.getRejectedQuantity());
        BigDecimal damaged = nvl(item.getDamagedQuantity());
        BigDecimal processed = physicalReceived;
        BigDecimal open = FulfillmentQuantities.physicalReceiptRemaining(item);
        String shortageReason = item.getShortageReason();
        // Excess only materializes once a receipt is posted against this line; open (not
        // yet posted) context lines cannot have received more than ordered, so it is always
        // zero here. Posted excess is exposed on ReceiptLineContext instead.
        BigDecimal excess = BigDecimal.ZERO;
        BigDecimal shortage = shortageReason == null || shortageReason.isBlank() ? BigDecimal.ZERO : open;
        return new GoodsIncomeLine(
            item.getId(),
            item.getCode(),
            displayName(item.getName(), item.getCode()),
            material == null ? null : ref(material.getId(), material.getCode(), displayName(material.getName(), material.getCode()), material.getStatus()),
            group == null ? null : ref(group.getId(), group.getCode(), displayName(group.getName(), group.getCode()), group.getStatus()),
            item.getUnit(),
            item.getTrackingModeSnapshot(),
            batchTrackingSnapshot(item),
            serialTrackingSnapshot(item),
            nvl(item.getQuantity()),
            accepted,
            rejected,
            damaged,
            processed,
            open,
            shortageReason,
            item.getReceiptBatchNumber(),
            item.getReceiptWarehouseId(),
            item.getReceiptStorageLocationId(),
            item.getQualityStatus(),
            item.getStatus(),
            accreditationEligible,
            accreditationMessage,
            physicalReceived,
            shortage,
            excess
        ).withUnitShortName(unitDisplay.shortName(item, item.getUnit(), material));
    }

    private BatchTrackingPolicy batchTrackingSnapshot(PurchaseOrderItem item) {
        if (item.getBatchTrackingSnapshot() != null) {
            return item.getBatchTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.batchTrackingPolicy(item.getTrackingModeSnapshot());
        }
        Material material = item.getMaterial();
        if (material != null && material.getBatchTrackingPolicy() != null) {
            return material.getBatchTrackingPolicy();
        }
        return material == null ? BatchTrackingPolicy.NONE : TrackingMode.batchTrackingPolicy(material.getTrackingMode());
    }

    private SerialTrackingPolicy serialTrackingSnapshot(PurchaseOrderItem item) {
        if (item.getSerialTrackingSnapshot() != null) {
            return item.getSerialTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.serialTrackingPolicy(item.getTrackingModeSnapshot());
        }
        Material material = item.getMaterial();
        if (material != null && material.getSerialTrackingPolicy() != null) {
            return material.getSerialTrackingPolicy();
        }
        return material == null ? SerialTrackingPolicy.NONE : TrackingMode.serialTrackingPolicy(material.getTrackingMode());
    }

    private AccreditationContext accreditation(
        UUID companyId,
        UUID supplierId,
        List<PurchaseOrderItem> items,
        String blockedReason,
        LocalDate asOfDate
    ) {
        List<SupplierAccreditationApplication> applications = accreditationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                companyId,
                supplierId,
                ELIGIBLE_ACCREDITATION_STATUSES
            );
        SupplierAccreditationApplication application = applications.isEmpty() ? null : applications.get(0);
        SupplierAccreditationDecision decision = application == null
            ? null
            : accreditationDecisionRepository
                .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), companyId, application.getId()
                ).stream().findFirst().orElse(null);
        List<EntityRef> groups = items.stream()
            .map(PurchaseOrderItem::getMaterial)
            .filter(Objects::nonNull)
            .map(material -> material.getMaterialGroup())
            .filter(Objects::nonNull)
            .map(group -> ref(group.getId(), group.getCode(), displayName(group.getName(), group.getCode()), group.getStatus()))
            .distinct()
            .toList();
        List<UUID> materialGroupIds = items.stream()
            .map(PurchaseOrderItem::getMaterial)
            .filter(Objects::nonNull)
            .map(material -> material.getMaterialGroupId())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        // Same dated/scoped eligibility snapshot the PO and supplier governance surfaces read
        // (UX-LIVE-02): eligible mirrors eligibleForRequestedScope so the workspace can never
        // disagree with the dossier about whether receipt is currently allowed.
        EffectiveSupplierEligibility eligibility = accreditationService.effectiveEligibility(
            supplierId, materialGroupIds, asOfDate, "goods receipt");
        return new AccreditationContext(
            eligibility.eligibleForRequestedScope(),
            application == null ? "MISSING" : application.getStatus(),
            blockedReason,
            application == null ? null : application.getId(),
            application == null ? null : application.getCode(),
            decision == null
                ? application == null ? null : application.getRequestedScope()
                : decision.getApprovedScope(),
            groups,
            decision != null && decision.getValidTo() != null
                ? decision.getValidTo()
                : application == null ? null : application.getValidTo(),
            decision == null ? null : decision.getConditions(),
            eligibility.overallStatus()
        );
    }

    private List<WorkspaceBlocker> blockers(
        PurchaseOrder po,
        List<PurchaseOrderItem> allItems,
        List<PurchaseOrderItem> items,
        List<AdvanceShippingNotice> asns,
        String accreditationBlockedReason
    ) {
        java.util.ArrayList<WorkspaceBlocker> blockers = new java.util.ArrayList<>();
        if (!List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name()).contains(po.getStatus())) {
            blockers.add(new WorkspaceBlocker(
                "PURCHASE_ORDER_NOT_RECEIVABLE", "ERROR", "Purchase order is not ready for receipt", "PROCUREMENT_MANAGER", "OPEN_PURCHASE_ORDER"
            ));
        }
        boolean hasFixedAssetLines = allItems != null && allItems.stream()
            .anyMatch(item -> item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET);
        boolean hasServiceLines = allItems != null && allItems.stream().anyMatch(this::isServiceLine);
        if (items.isEmpty() && hasFixedAssetLines && !hasServiceLines) {
            blockers.add(new WorkspaceBlocker(
                "FIXED_ASSET_RECEIVING_REQUIRED", "ERROR", "This purchase order contains fixed assets. Use Asset Acceptance.", "WAREHOUSE_OPERATOR", "OPEN_ASSET_ACCEPTANCE"
            ));
        } else if (items.isEmpty() && hasServiceLines && !hasFixedAssetLines) {
            blockers.add(new WorkspaceBlocker(
                "SERVICE_ACCEPTANCE_REQUIRED", "ERROR", "This purchase order contains services. Use Service Acceptance.", "PROCUREMENT_SPECIALIST", "OPEN_SERVICE_ACCEPTANCE"
            ));
        } else if (items.isEmpty() || items.stream().noneMatch(item -> openQuantity(item).signum() > 0)) {
            blockers.add(new WorkspaceBlocker(
                "NO_OPEN_MATERIAL_LINES", "ERROR", "Purchase order has no open material lines", "PROCUREMENT_SPECIALIST", "REVIEW_PURCHASE_ORDER_LINES"
            ));
        }
        String asnBlockedReason = asnRequiredBlockedReason(asns);
        if (asnBlockedReason != null) {
            blockers.add(new WorkspaceBlocker(
                "ASN_CONFIRMATION_REQUIRED", "ERROR", asnBlockedReason, "PROCUREMENT_MANAGER", "CONFIRM_DELIVERY"
            ));
        }
        if (accreditationBlockedReason != null) {
            blockers.add(new WorkspaceBlocker(
                "SUPPLIER_ACCREDITATION_BLOCKED", "ERROR", accreditationBlockedReason, "SUPPLIER_MANAGER", "OPEN_SUPPLIER_GOVERNANCE"
            ));
        }
        return List.copyOf(blockers);
    }

    private WorkspaceAction receiveAction(PurchaseOrder po, List<PurchaseOrderItem> items, List<WorkspaceBlocker> blockers) {
        boolean roleAllowed = hasAnyRole(RECEIVE_ROLES);
        String disabledReason = !blockers.isEmpty()
            ? blockers.get(0).message()
            : !roleAllowed ? "Current role cannot post a goods receipt" : null;
        return new WorkspaceAction(
            "RECEIVE",
            "goodsIncome.action.receive",
            disabledReason == null,
            disabledReason,
            RECEIVE_ROLES,
            List.of("warehouseId", "storageLocationId", "asnId", "lines", "version"),
            List.of("PURCHASE_ORDER_ATTACHMENT_FOR_EXCEPTIONS"),
            "Posts accepted quantities to stock and emits accounting and treasury integration events",
            true,
            true
        );
    }

    private List<AdvanceShippingNotice> asns(PurchaseOrder po) {
        return asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getCompanyId(), po.getId()
        );
    }

    private String asnRequiredBlockedReason(List<AdvanceShippingNotice> asns) {
        if (asns == null || asns.isEmpty()) {
            return "Confirmed ASN is required before goods receipt";
        }
        return asns.stream().anyMatch(this::isReceiptEligibleAsn)
            ? null
            : "Confirmed ASN is required before goods receipt";
    }

    private boolean isReceiptEligibleAsn(AdvanceShippingNotice asn) {
        return asn != null && List.of("CONFIRMED", "PARTIALLY_RECEIVED").contains(asn.getStatus());
    }

    private String asnReceiptBlockedReason(AdvanceShippingNotice asn) {
        return isReceiptEligibleAsn(asn) ? null : "ASN must be CONFIRMED before goods receipt";
    }

    private List<EvidenceItem> evidence(UUID companyId, UUID purchaseOrderId) {
        return attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, "PURCHASEORDER", purchaseOrderId
            ).stream().map(attachment -> new EvidenceItem(
                attachment.getId(),
                displayName(attachment.getOriginalFileName(), attachment.getName(), attachment.getCode()),
                attachment.getAttachmentType(),
                attachment.getContentType(),
                attachment.getUploadedAt(),
                attachment.isPreviewable(),
                attachment.isDownloadable()
            )).toList();
    }

    private List<StockMovementContext> stockMovements(UUID companyId, UUID purchaseOrderId) {
        return stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), "PurchaseOrder", purchaseOrderId
        ).stream()
            .filter(movement -> Objects.equals(movement.getCompanyId(), companyId))
            .map(movement -> new StockMovementContext(
                movement.getId(),
                movement.getCode(),
                movement.getMovementType() == null ? null : movement.getMovementType().name(),
                movement.getStatus(),
                movement.getMaterialId(),
                movement.getWarehouseId(),
                movement.getStorageLocationId(),
                movement.getStorageBinId(),
                nvl(movement.getQuantity()),
                movement.getBatchNumber(),
                movement.getMovementDate(),
                movement.getPostedAt()
            )).toList();
    }

    private List<AuditTimelineItem> auditTimeline(UUID companyId, UUID purchaseOrderId) {
        return auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, "PurchaseOrder", purchaseOrderId
        ).stream().map(log -> new AuditTimelineItem(
            log.getId(), log.getAction(), log.getFullName(), log.getDetails(), log.getOccurredAt()
        )).toList();
    }

    private List<IntegrationImpactItem> integrationImpact(UUID companyId, UUID purchaseOrderId) {
        return integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, purchaseOrderId
            ).stream().map(event -> {
                // UX-LIVE-03: resolve the related business document so a stalled/failed event
                // still tells the FE which document it concerns and whether it needs recovery.
                BusinessDocumentReference document = documentResolver.resolve(event);
                return new IntegrationImpactItem(
                    event.getId(),
                    event.getCode(),
                    event.getEventType(),
                    event.getStatus(),
                    event.getRetryCount(),
                    event.getLastError(),
                    event.getCreatedAt(),
                    document == null ? null : document.type(),
                    document == null ? null : document.code(),
                    document != null && document.identityAvailable(),
                    IntegrationRecoveryStatus.fromEventStatus(event.getStatus())
                );
            }).toList();
    }

    private boolean hasAnyRole(List<String> roles) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(java.util.stream.Collectors.toSet());
        return roles.stream().map(role -> "ROLE_" + role).anyMatch(authorities::contains);
    }

    private boolean isMaterialLine(PurchaseOrderItem item) {
        return Optional.ofNullable(item.getLineType()).orElse(ProcurementLineType.MATERIAL) == ProcurementLineType.MATERIAL;
    }

    private boolean isTmbMaterialLine(PurchaseOrderItem item) {
        return isMaterialLine(item) && item.getMaterialTypeSnapshot() == MaterialType.TMB;
    }

    private boolean isServiceLine(PurchaseOrderItem item) {
        return Optional.ofNullable(item.getLineType()).orElse(ProcurementLineType.MATERIAL) == ProcurementLineType.SERVICE
            || item.getMaterialTypeSnapshot() == MaterialType.SERVICES;
    }

    private BigDecimal openQuantity(PurchaseOrderItem item) {
        return FulfillmentQuantities.physicalReceiptRemaining(item);
    }

    private EntityRef ref(UUID id, String code, String displayName, String status) {
        return new EntityRef(id, code, displayName(displayName, code), status);
    }

    private String supplierDisplayName(Supplier supplier) {
        return displayName(supplier.getName(), supplier.getShortName(), supplier.getLegalName(), supplier.getCode());
    }

    private String displayName(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "вЂ”";
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String dataScope() {
        return AccessScope.companyScoped(true)
            ? "COMPANY:" + CompanyContext.currentCompany()
            : "GLOBAL";
    }
}
