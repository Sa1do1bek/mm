package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class InventoryPostingReadinessService {
    public record Adjustment(UUID lineId, UUID materialId, UUID warehouseId, UUID storageLocationId,
        UUID storageBinId, UUID stockBatchId, BatchTrackingPolicy batchTrackingPolicy,
        SerialTrackingPolicy serialTrackingPolicy, String materialName, String unitCode, String warehouseName,
        BigDecimal quantity, BigDecimal unitCost, BigDecimal amount, String currencyCode, String kind) {}
    public record Readiness(boolean ready, List<String> blockers, LocalDate postingDate, long countRevision,
        Long approvedCountRevision, List<Adjustment> adjustments) {}
    private final InventoryAccessService access;
    private final InventoryItemRepository items;
    private final InventoryEvidenceService evidence;
    private final InventoryScopeService scope;
    private final RoleDataScopeService authority;
    private final InventoryPeriodGuardService periods;
    private final InventoryAccountingSettingsService settings;
    private final InventoryStockAsOfQuery stockAsOfQuery;

    public Readiness get(UUID id) { return get(id, null); }
    public Readiness get(UUID id, LocalDate date) { var d = access.document(id); access.requireVisibleExport(d); return inspect(d, date); }
    public void requirePostAuthority(InventoryDocument d) {
        access.requireVisibleExport(d);
        if (!access.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")) throw new org.springframework.security.access.AccessDeniedException("Finance authority is required to post inventory adjustments");
    }
    public Readiness inspect(InventoryDocument d, LocalDate date) {
        var blockers = new LinkedHashSet<String>(); var rows = items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), d.getId());
        if (!"APPROVED".equals(d.getStatus())) blockers.add("COUNT_NOT_APPROVED");
        if (!revisionsMatch(d.getCountRevision(), d.getApprovedCountRevision()) || !revisionsMatch(d.getCountRevision(), d.getSubmittedCountRevision())) blockers.add("APPROVAL_REVISION_STALE");
        if (d.getSnapshotAt() == null || !d.isFreezeOperations()) blockers.add("UNFROZEN_SNAPSHOT_REQUIRES_RESTART");
        if (d.getStockAsOfDate() != null && d.getSnapshotAt() != null
            && stockAsOfQuery.hasLateBackdatedMovement(d.getTenantId(), d.getCompanyId(),
                d.isGlobalWarehouse() ? List.of() : scope.warehouseIds(d), d.getStockAsOfDate(), d.getSnapshotAt()))
            blockers.add("AS_OF_SNAPSHOT_STALE");
        if (rows.isEmpty()) blockers.add("SNAPSHOT_EMPTY");
        if (d.getUnidentifiedStock().stream().anyMatch(o -> o.resolvedLineId() == null)) blockers.add("UNIDENTIFIED_STOCK");
        if (date == null) blockers.add("POSTING_DATE_REQUIRED");
        var changes = new ArrayList<Adjustment>(); String currency = settings.findOrDefault().getBaseCurrencyCode();
        for (var line : rows) {
            if (!d.getTenantId().equals(line.getTenantId()) || !d.getCompanyId().equals(line.getCompanyId()) || line.getWarehouseId() == null || !authority.canAccessWarehouse(line.getWarehouseId()) || !scope.contains(d, line.getWarehouseId(), line.getStorageLocationId(), line.getStorageBinId(), line.getMaterialId())) { blockers.add("STOCK_SCOPE_INVALID"); continue; }
            if (date != null && periods.isClosed(line.getWarehouseId(), date)) blockers.add("POSTING_PERIOD_CLOSED");
            if (line.getCountedQuantity() == null || line.getLineStatus() == InventoryLineStatus.NEEDS_RECOUNT) { blockers.add("UNCOUNTED_LINES"); continue; }
            if (line.getSystemQuantity() == null) { blockers.add("SNAPSHOT_INVALID"); continue; }
            boolean serial = line.getObservedSerialNumber() != null || line.getStockBatch() != null && line.getStockBatch().getSerialNumber() != null;
            try { InventoryCountMutationService.validateQuantity(line.getCountedQuantity(), false, InventoryCountMutationService.quantityScale(line), serial); } catch (RuntimeException invalid) { blockers.add("COUNT_PRECISION_INVALID"); }
            BigDecimal difference = line.getCountedQuantity().subtract(line.getSystemQuantity());
            if (line.getDifferenceQuantity() == null || difference.compareTo(line.getDifferenceQuantity()) != 0) blockers.add("COUNT_REVISION_INVALID");
            if (line.getSourceCorrectionMovementId() != null || line.getReconciledSourceMovementId() != null) blockers.add("SOURCE_CORRECTION_REQUIRES_RECONCILIATION");
            if (difference.signum() == 0) continue;
            if (line.getLineStatus() != InventoryLineStatus.RESOLVED || !InventoryReviewService.isPostingDecision(line.getDecisionType()) || !revisionsMatch(line.getCountRevision(), line.getDecisionCountRevision()) || line.getReviewBlocker() != null) blockers.add("UNRESOLVED_DIFFERENCES");
            try { InventoryReviewService.validateDecisionSign(line.getDecisionType(), difference); InventoryReviewService.reason(line.getDecisionComment()); evidence.validate(d, line.getId(), line.getDecisionEvidenceIds(), true); } catch (RuntimeException invalid) { blockers.add("DECISION_EVIDENCE_REQUIRED"); }
            if (Set.of(InventoryExplanationStatus.REQUESTED, InventoryExplanationStatus.PROVIDED, InventoryExplanationStatus.REJECTED).contains(line.getExplanationStatus())) blockers.add("EXPLANATION_PENDING");
            if (line.getLinkedStockMovementId() != null) blockers.add("POSTING_LINK_REQUIRES_RECONCILIATION");
            if (difference.signum() > 0 && (line.getUnitCost() == null || line.getUnitCost().signum() <= 0)) blockers.add("SURPLUS_VALUATION_REQUIRED");
            if (line.isFoundStock()) {
                if (!revisionsMatch(line.getCountRevision(), line.getValuationCountRevision()) || line.getValuedBy() == null) blockers.add("FOUND_STOCK_VALUATION_REQUIRED");
                try { InventoryReviewService.reason(line.getValuationBasis()); evidence.validate(d, line.getId(), line.getValuationEvidenceIds(), true); evidence.validate(d, line.getId(), line.getFoundEvidenceIds(), true); } catch (RuntimeException invalid) { blockers.add("FOUND_STOCK_VALUATION_REQUIRED"); }
                var expiry = line.getObservedExpiryDate() != null ? line.getObservedExpiryDate() : line.getStockBatch() == null ? null : line.getStockBatch().getExpirationDate();
                if (expiry != null && date != null && expiry.isBefore(date)) blockers.add("FOUND_STOCK_EXPIRED_REQUIRES_QUALITY_REVIEW");
            }
            var unitCost = line.getUnitCost() == null ? BigDecimal.ZERO : line.getUnitCost();
            var material = line.getMaterial();
            changes.add(new Adjustment(line.getId(), line.getMaterialId(), line.getWarehouseId(),
                line.getStorageLocationId(), line.getStorageBinId(), line.getStockBatchId(),
                material == null ? null : material.getBatchTrackingPolicy(),
                material == null ? null : material.getSerialTrackingPolicy(),
                material == null ? line.getName() : material.getName(),
                material == null || material.getUnitOfMeasure() == null ? null : material.getUnitOfMeasure().getCode(),
                line.getWarehouse() == null ? null : line.getWarehouse().getName(), difference.abs(), unitCost,
                difference.abs().multiply(unitCost), currency, difference.signum() < 0 ? "SHORTAGE" : "SURPLUS"));
        }
        return new Readiness(blockers.isEmpty(), List.copyOf(blockers), date, d.getCountRevision(), d.getApprovedCountRevision(), changes);
    }
    public void requireReady(InventoryDocument d, LocalDate date) {
        requirePostAuthority(d); var state = inspect(d, date);
        if (!state.ready()) throw new BusinessException("Inventory posting is blocked: " + String.join(", ", state.blockers()), "INVENTORY_POSTING_NOT_READY", Map.of("blockers", state.blockers()));
    }
    public static boolean revisionsMatch(long current, Long approved) { return approved != null && approved == current; }
}
