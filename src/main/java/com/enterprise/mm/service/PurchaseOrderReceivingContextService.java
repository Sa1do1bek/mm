package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ReceivingMode;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PoReceivingContextResponse;
import com.enterprise.mm.payload.PoReceivingContextResponse.*;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderReceivingContextService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderReceivingModeService receivingModeService;
    private final GoodsIncomeWorkspaceService goodsIncomeWorkspaceService;

    // Asset
    private final AssetAcceptanceRepository assetAcceptanceRepository;
    private final AssetAcceptanceLineRepository assetAcceptanceLineRepository;
    private final AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;
    private final AssetManagementSettingsService assetSettingsService;

    // Service
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;

    // ASN
    private final AdvanceShippingNoticeRepository asnRepository;

    @Transactional(readOnly = true)
    public PoReceivingContextResponse buildContext(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(entity -> tenantId.equals(entity.getTenantId()))
            .filter(entity -> AccessScope.canAccessCompany(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + purchaseOrderId));

        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, po.getId())
            .stream()
            .filter(item -> Objects.equals(item.getCompanyId(), po.getCompanyId()))
            .toList();

        ReceivingMode mode = receivingModeService.determineReceivingMode(items);

        PoReceivingHeader header = new PoReceivingHeader(
            po.getId(),
            po.getCode(),
            po.getName(),
            po.getStatus(),
            po.getVersion(),
            po.getSupplier() != null ? new SupplierSummary(
                po.getSupplier().getId(),
                po.getSupplier().getCode(),
                po.getSupplier().getName(),
                po.getSupplier().getStatus()
            ) : null,
            po.getOrderDate(),
            po.getExpectedDeliveryDate(),
            mode.name(),
            po.getTotalAmount(),
            po.getCurrencyCode()
        );

        List<PoItemLine> lines = new ArrayList<>();
        AccreditationInfo accreditation = null;
        List<AsnSummary> advanceShippingNotices = new ArrayList<>();
        List<EvidenceSummary> evidence = new ArrayList<>();
        List<ReceiptSummary> receipts = new ArrayList<>();
        List<StockMovementSummary> stockMovements = new ArrayList<>();
        List<AuditTimelineSummary> auditTimeline = new ArrayList<>();
        List<IntegrationImpactSummary> integrationImpact = new ArrayList<>();
        List<WorkspaceBlocker> blockers = new ArrayList<>();
        List<WorkspaceAction> allowedActions = new ArrayList<>();

        GoodsReceiptContext goodsReceipt = null;
        AssetAcceptanceContext assetAcceptance = null;
        ServiceAcceptanceContext serviceAcceptance = null;

        if (mode == ReceivingMode.GOODS_RECEIPT) {
            goodsReceipt = buildGoodsReceiptContext(po.getId(), lines, advanceShippingNotices, evidence, receipts, stockMovements, auditTimeline, integrationImpact, blockers, allowedActions);
            GoodsIncomeContext gi = goodsIncomeWorkspaceService.context(po.getId());
            if (gi.accreditation() != null) {
                accreditation = new AccreditationInfo(
                    gi.accreditation().eligible(),
                    gi.accreditation().status(),
                    gi.accreditation().blockedReason(),
                    gi.accreditation().applicationId(),
                    gi.accreditation().applicationCode(),
                    gi.accreditation().approvedScope(),
                    gi.accreditation().materialGroups() != null ? gi.accreditation().materialGroups().stream().map(mg -> new MaterialGroupSummary(mg.id(), mg.code(), mg.displayName(), mg.status())).toList() : null,
                    gi.accreditation().validTo(),
                    gi.accreditation().conditions(),
                    gi.accreditation().overallStatus()
                );
            }
        } else if (mode == ReceivingMode.ASSET_ACCEPTANCE) {
            populateLinesFromItems(items, lines);
            assetAcceptance = buildAssetAcceptanceContext(tenantId, companyId, po, blockers, allowedActions, advanceShippingNotices);
        } else if (mode == ReceivingMode.SERVICE_ACCEPTANCE) {
            populateLinesFromItems(items, lines);
            serviceAcceptance = buildServiceAcceptanceContext(tenantId, companyId, po, blockers, allowedActions);
        }

        return new PoReceivingContextResponse(
            mode.name(),
            header,
            lines.isEmpty() ? null : lines,
            accreditation,
            advanceShippingNotices.isEmpty() ? null : advanceShippingNotices,
            evidence.isEmpty() ? null : evidence,
            receipts.isEmpty() ? null : receipts,
            stockMovements.isEmpty() ? null : stockMovements,
            auditTimeline.isEmpty() ? null : auditTimeline,
            integrationImpact.isEmpty() ? null : integrationImpact,
            blockers,
            allowedActions,
            goodsReceipt,
            assetAcceptance,
            serviceAcceptance,
            LocalDateTime.now()
        );
    }

    private GoodsReceiptContext buildGoodsReceiptContext(
        UUID poId,
        List<PoItemLine> lines,
        List<AsnSummary> advanceShippingNotices,
        List<EvidenceSummary> evidence,
        List<ReceiptSummary> receipts,
        List<StockMovementSummary> stockMovements,
        List<AuditTimelineSummary> auditTimeline,
        List<IntegrationImpactSummary> integrationImpact,
        List<WorkspaceBlocker> blockers,
        List<WorkspaceAction> actions
    ) {
        GoodsIncomeContext gi = goodsIncomeWorkspaceService.context(poId);

        if (gi.lines() != null) {
            lines.addAll(gi.lines().stream().map(line -> new PoItemLine(
                line.id(),
                line.code(),
                line.displayName(),
                line.material() != null ? new MaterialSummary(line.material().id(), line.material().code(), line.material().displayName(), line.material().status()) : null,
                line.materialGroup() != null ? new MaterialGroupSummary(line.materialGroup().id(), line.materialGroup().code(), line.materialGroup().displayName(), line.materialGroup().status()) : null,
                line.unit(),
                line.trackingModeSnapshot() != null ? line.trackingModeSnapshot().name() : null,
                line.batchTrackingSnapshot() != null ? line.batchTrackingSnapshot().name() : null,
                line.serialTrackingSnapshot() != null ? line.serialTrackingSnapshot().name() : null,
                line.orderedQuantity(),
                line.acceptedQuantity(),
                line.rejectedQuantity(),
                line.damagedQuantity(),
                line.processedQuantity(),
                line.openQuantity(),
                line.shortageReason(),
                line.batchNumber(),
                line.receiptWarehouseId(),
                line.receiptStorageLocationId(),
                line.qualityStatus(),
                line.lineStatus(),
                line.accreditationEligible(),
                line.accreditationMessage(),
                line.previouslyReceivedQuantity(),
                line.shortageQuantity(),
                line.excessQuantity(),
                line.acceptedShortfallQuantity()
            ).withUnitShortName(line.unitShortName())).toList());
        }

        if (gi.advanceShippingNotices() != null) {
            advanceShippingNotices.addAll(gi.advanceShippingNotices().stream().map(asn -> new AsnSummary(
                asn.id(),
                asn.code(),
                asn.displayName(),
                asn.status(),
                asn.expectedArrivalDate(),
                asn.carrierName(),
                asn.vehicleNumber(),
                asn.receiptEligible(),
                asn.receiptBlockedReason(),
                asn.expectedBatches() != null ? asn.expectedBatches().stream().map(b -> b.batchNumber()).toList() : null,
                asn.expectedSerials() != null ? asn.expectedSerials().stream().map(s -> s.manufacturerSerialNumber()).toList() : null
            )).toList());
        }

        if (gi.blockers() != null) {
            blockers.addAll(gi.blockers().stream().map(b -> new WorkspaceBlocker(b.code(), b.severity(), b.message(), b.resolutionAction())).toList());
        }

        if (gi.allowedActions() != null) {
            actions.addAll(gi.allowedActions().stream().map(a -> new WorkspaceAction(
                a.code(), 
                a.labelKey(), 
                a.enabled(), 
                a.disabledReason(), 
                a.requiredRoles(),
                a.requiredFields(),
                a.requiredEvidence(),
                a.confirmationImpact(),
                a.irreversible(),
                a.idempotencyRequired()
            )).toList());
        }

        if (gi.receipts() != null) {
            receipts.addAll(gi.receipts().stream().map(r -> new ReceiptSummary(
                r.id(), r.code(), r.postedAt(), r.postedBy(),
                r.lines() != null ? r.lines().stream().map(l -> new ReceiptLineSummary(
                    l.materialId(), null, l.uomCode(),
                    l.acceptedQuantity(), l.rejectedQuantity(), l.damagedQuantity(), l.qualityStatus()
                ).withUnitShortName(l.unitShortName())).toList() : null
            )).toList());
        }

        if (gi.evidence() != null) {
            evidence.addAll(gi.evidence().stream().map(e -> new EvidenceSummary(e.id(), e.displayName(), e.attachmentType(), e.contentType(), e.uploadedAt())).toList());
        }

        if (gi.stockMovements() != null) {
            stockMovements.addAll(gi.stockMovements().stream().map(m -> new StockMovementSummary(m.id(), m.movementType(), m.quantity(), null, m.movementDate())).toList());
        }

        if (gi.auditTimeline() != null) {
            auditTimeline.addAll(gi.auditTimeline().stream().map(a -> new AuditTimelineSummary(a.id(), a.action(), a.actor(), a.details(), a.occurredAt())).toList());
        }

        if (gi.integrationImpact() != null) {
            integrationImpact.addAll(gi.integrationImpact().stream().map(i -> new IntegrationImpactSummary(
                i.id(), i.code(), i.eventType(), i.status(), i.retryCount(), i.lastError(), i.createdAt(), i.businessDocumentType(), i.businessDocumentCode(), i.identityAvailable(), i.recoveryStatus()
            )).toList());
        }

        return new GoodsReceiptContext(blockers.isEmpty());
    }

    private AssetAcceptanceContext buildAssetAcceptanceContext(
        String tenantId, UUID companyId, PurchaseOrder po,
        List<WorkspaceBlocker> blockers, List<WorkspaceAction> actions, List<AsnSummary> asnSummaries
    ) {
        List<AssetAcceptance> rawAcceptances = assetAcceptanceRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId, po.getId());

        List<AcceptanceSummary> acceptances = new ArrayList<>();
        AcceptanceSummary activeAcceptance = null;

        for (AssetAcceptance acc : rawAcceptances) {
            List<AssetAcceptanceLine> rawLines = assetAcceptanceLineRepository
                .findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(tenantId, companyId, acc.getId());
            
            List<AcceptanceLineSummary> lineSummaries = new ArrayList<>();
            for (AssetAcceptanceLine line : rawLines) {
                List<AssetAcceptanceUnit> rawUnits = assetAcceptanceUnitRepository
                    .findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(tenantId, companyId, line.getId());
                
                List<AcceptanceUnitSummary> unitSummaries = rawUnits.stream()
                    .map(u -> new AcceptanceUnitSummary(
                        u.getId(),
                        u.getReceivingUnitNumber(),
                        u.getManufacturerSerialNumber(),
                        u.getCondition(),
                        u.getQualityDecision() != null ? u.getQualityDecision().name() : null,
                        u.getQualityDecisionAt(),
                        u.getQualityDecisionBy(),
                        u.getPostedFixedAssetId(),
                        null 
                    )).toList();
                
                lineSummaries.add(new AcceptanceLineSummary(
                    line.getId(),
                    line.getPurchaseOrderItemId(),
                    line.getMaterialId(),
                    line.getMaterial() != null ? line.getMaterial().getName() : null,
                    line.getUnit(),
                    null, 
                    line.getDeliveredQuantity(),
                    line.getAcceptedQuantity(),
                    line.getRejectedQuantity(),
                    line.getPendingQuantity(),
                    line.getHoldQuantity(),
                    line.getQualityDecision() != null ? line.getQualityDecision().name() : null,
                    line.getAcceptedValue(),
                    line.getAcceptedCurrencyCode(),
                    unitSummaries
                ).withUnitShortName(unitDisplay.shortName(line, line.getUnit(), line.getMaterial())));
            }

            AcceptanceSummary summary = new AcceptanceSummary(
                acc.getId(),
                acc.getApprovalStatus() != null ? acc.getApprovalStatus().name() : null,
                acc.getPostingStatus() != null ? acc.getPostingStatus().name() : null,
                acc.getWarehouseId(),
                acc.getWarehouse() != null ? acc.getWarehouse().getName() : null,
                acc.getAsnId(),
                null, 
                acc.getReceiptDate(),
                acc.getReceivedAt(),
                acc.getSourceEvidence(),
                acc.getDeliveryReference(),
                acc.getPostedAt(),
                acc.getPostedBy(),
                lineSummaries
            );
            acceptances.add(summary);

            if (activeAcceptance == null && ("DRAFT".equals(summary.approvalStatus()) || "SUBMITTED".equals(summary.approvalStatus()))) {
                activeAcceptance = summary;
            }
        }

        List<AdvanceShippingNotice> asns = asnRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, companyId, po.getId());
        
        asnSummaries.addAll(asns.stream()
            .map(asn -> new AsnSummary(
                asn.getId(),
                asn.getAsnNumber(),
                asn.getAsnNumber(),
                asn.getStatus(),
                asn.getExpectedArrivalDate(),
                asn.getCarrierName(),
                asn.getVehicleNumber(),
                "CONFIRMED".equals(asn.getStatus()) || "PARTIALLY_RECEIVED".equals(asn.getStatus()),
                null, null, null
            )).toList());

        for (int i = 0; i < acceptances.size(); i++) {
            AcceptanceSummary s = acceptances.get(i);
            if (s.asnId() != null) {
                String num = asns.stream().filter(a -> a.getId().equals(s.asnId())).map(AdvanceShippingNotice::getAsnNumber).findFirst().orElse(null);
                acceptances.set(i, new AcceptanceSummary(s.id(), s.approvalStatus(), s.postingStatus(), s.warehouseId(), s.warehouseName(), s.asnId(), num, s.receiptDate(), s.receivedAt(), s.sourceEvidence(), s.deliveryReference(), s.postedAt(), s.postedBy(), s.lines()));
                if (activeAcceptance != null && activeAcceptance.id().equals(s.id())) {
                    activeAcceptance = acceptances.get(i);
                }
            }
        }

        boolean canCreate = activeAcceptance == null;
        actions.add(new WorkspaceAction("CREATE_ACCEPTANCE", "assetAcceptance.action.create", canCreate, canCreate ? null : "Draft already exists", null, null, null, null, false, false));

        AssetSettings settings = null;
        try {
            AssetManagementSettings rawSettings = assetSettingsService.requireConfigured();
            settings = new AssetSettings(
                rawSettings.getCentralWarehouseId(),
                rawSettings.getCentralWarehouse() != null ? rawSettings.getCentralWarehouse().getName() : null,
                rawSettings.isRequireSerialNumber(),
                rawSettings.isAutoNumberAtReceipt(),
                rawSettings.getInventoryNumberPrefix(),
                true,
                true 
            );
        } catch (Exception ex) {
            settings = new AssetSettings(null, null, false, false, "FA", false, true);
            blockers.add(new WorkspaceBlocker("SETTINGS_MISSING", "ERROR", "Fixed asset settings are missing", "Configure settings"));
        }

        boolean hasEligibleAsn = asnSummaries.stream().anyMatch(AsnSummary::receiptEligible);
        if (!hasEligibleAsn && settings.asnRequired()) {
            blockers.add(new WorkspaceBlocker("NO_ELIGIBLE_ASN", "WARNING", "No confirmed ASN available for asset acceptance", "Request ASN confirmation"));
        }

        return new AssetAcceptanceContext(acceptances, activeAcceptance, settings);
    }

    private ServiceAcceptanceContext buildServiceAcceptanceContext(String tenantId, UUID companyId, PurchaseOrder po, List<WorkspaceBlocker> blockers, List<WorkspaceAction> actions) {
        List<ServiceAcceptanceAct> rawActs = serviceAcceptanceActRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId, po.getId());

        List<ServiceActSummary> acts = rawActs.stream()
            .map(act -> new ServiceActSummary(
                act.getId(),
                act.getCode(),
                act.getName(),
                act.getStatus(),
                act.getServiceOrderId(),
                null, 
                act.getContractId(),
                null,
                act.getAmount(),
                act.getVatAmount(),
                act.getVatRate(),
                act.getPeriodStart(),
                act.getPeriodEnd(),
                act.getStage(),
                act.getAcceptedAt(),
                act.getAcceptedBy(),
                act.getDocumentReviewedAt(),
                act.getFinanceReviewedAt(),
                act.getSentToPaymentAt(),
                act.getPaidAt(),
                act.getPaymentReference(),
                act.getCommissionNotes(),
                act.getRemarks()
            )).toList();

        ServiceActSummary activeAct = acts.stream()
            .filter(a -> "DRAFT".equals(a.status()) || "APPROVAL_PENDING".equals(a.status()))
            .findFirst().orElse(null);

        BigDecimal accepted = BigDecimal.ZERO;
        for (ServiceActSummary a : acts) {
            if ("ACCEPTED".equals(a.status()) || "PAID".equals(a.status()) || "PAYMENT_READY".equals(a.status())) {
                accepted = accepted.add(a.amount() != null ? a.amount() : BigDecimal.ZERO);
            }
        }
        
        BigDecimal ordered = po.getTotalAmount() != null ? po.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal remaining = ordered.subtract(accepted);

        ServiceAmountSummary summary = new ServiceAmountSummary(ordered, accepted, remaining, po.getCurrencyCode());
        
        actions.add(new WorkspaceAction("CREATE_ACT", "serviceAcceptance.action.create", activeAct == null, activeAct == null ? null : "Draft already exists", null, null, null, null, false, false));

        return new ServiceAcceptanceContext(acts, activeAct, summary);
    }

    private void populateLinesFromItems(List<PurchaseOrderItem> items, List<PoItemLine> lines) {
        lines.addAll(items.stream().map(item -> new PoItemLine(
            item.getId(),
            null,
            item.getMaterial() != null ? item.getMaterial().getName() : (item.getServiceDescription() != null ? item.getServiceDescription() : ""),
            item.getMaterial() != null ? new MaterialSummary(item.getMaterial().getId(), item.getMaterial().getCode(), item.getMaterial().getName(), item.getMaterial().getStatus()) : null,
            null,
            item.getUnit(),
            item.getTrackingModeSnapshot() != null ? item.getTrackingModeSnapshot().name() : null,
            item.getBatchTrackingSnapshot() != null ? item.getBatchTrackingSnapshot().name() : null,
            item.getSerialTrackingSnapshot() != null ? item.getSerialTrackingSnapshot().name() : null,
            item.getQuantity(),
            item.getAcceptedQuantity(),
            item.getRejectedQuantity(),
            item.getDamagedQuantity(),
            item.getReceivedQuantity(),
            item.getQuantity().subtract(item.getReceivedQuantity() != null ? item.getReceivedQuantity() : BigDecimal.ZERO),
            item.getShortageReason(),
            item.getReceiptBatchNumber(),
            item.getReceiptWarehouseId(),
            item.getReceiptStorageLocationId(),
            item.getQualityStatus(),
            "OPEN",
            false,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        ).withUnitShortName(unitDisplay.shortName(item, item.getUnit(), item.getMaterial()))).toList());
    }
}
