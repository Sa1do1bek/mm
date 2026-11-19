package com.enterprise.mm.service.inventory;

import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.inventory.policy.InventoryFreezePolicy;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.InventoryWorkspaceDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryWorkspaceService {
    private final InventoryAccessService access;
    private final InventoryScopeService scope;
    private final InventoryPostingReadinessService readiness;
    private final InventoryDocumentService documents;
    private final InventoryLineHistoryService history;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final StockBatchRepository batches;
    private final WarehouseRepository warehouses;
    private final RoleDataScopeService dataScope;
    private final AttachmentRepository attachments;

    public record BatchIdentity(UUID id, String name) {}

    @Transactional(readOnly = true)
    public PageResponse<BatchIdentity> batchCandidates(UUID id, UUID materialId, UUID warehouseId, String search, int page, int size) {
        var d = access.document(id);
        if (!scope.contains(d, warehouseId, d.getStorageLocationId(), d.getStorageBinId(), materialId)
            || !dataScope.canAccessWarehouse(warehouseId)
            || warehouses.findByIdAndIsDeletedFalse(warehouseId).filter(w -> d.getTenantId().equals(w.getTenantId()) && d.getCompanyId().equals(w.getCompanyId())).isEmpty())
            throw new org.springframework.security.access.AccessDeniedException("Batch identity is outside the inventory scope");
        var result = batches.findInventoryIdentityCandidates(d.getTenantId(), d.getCompanyId(), materialId, warehouseId,
            search == null ? "" : search.trim(),
            com.enterprise.mm.common.PaginationUtils.pageRequest(page, Math.min(size, 100), org.springframework.data.domain.Sort.by("batchNumber", "id")));
        return PageResponse.from(result.map(b -> new BatchIdentity(b.getId(), java.util.stream.Stream.of(
            b.getBatchNumber() == null ? b.getName() : b.getBatchNumber(), b.getSerialNumber(), b.getExpirationDate() == null ? null : b.getExpirationDate().toString())
            .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" · ")))));
    }

    @Value("${app.warehouse.inventory.workspace-enabled:true}") private boolean enabled;

    @Transactional(readOnly = true)
    public Context get(UUID id) {
        var entity = access.document(id);
        var document = documents.get(id);
        boolean visible = access.canSeeSystemQuantity(entity), review = access.canReview(entity);
        boolean editable = Set.of("IN_PROGRESS", "COUNTING_COMPLETED", "DISCREPANCY_REVIEW").contains(entity.getStatus());
        boolean canCount = editable && access.canCount(entity);
        var lines = document.getItems() == null ? List.<InventoryItemResponse>of() : document.getItems();
        if (!lines.isEmpty()) {
            var fileCounts = attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdInAndIsDeletedFalseOrderByCreatedAtDesc(
                entity.getTenantId(), entity.getCompanyId(), "INVENTORY_LINE_COUNT_EVIDENCE", lines.stream().map(InventoryItemResponse::getId).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(com.enterprise.mm.entity.Attachment::getEntityId, java.util.stream.Collectors.counting()));
            lines.forEach(line -> line.setCountEvidenceCount(fileCounts.getOrDefault(line.getId(), 0L).intValue()));
        }
        var historyRows = lines.stream().flatMap(line -> history.list(line.getId()).stream()).map(row -> InventoryReadPolicy.project(row, visible)).toList();
        boolean frozen = entity.getSnapshotAt() != null && InventoryFreezePolicy.holdsFreeze(InventoryStatus.from(entity.getStatus()), entity.isFreezeOperations());
        var freeze = new FreezeScope(frozen, entity.isFreezeOperations(), entity.isGlobalWarehouse(), entity.getWarehouseId(), document.getWarehouseName(), entity.getStorageLocationId(),
            entity.getStorageLocationId() == null ? null : locations.findByIdAndIsDeletedFalse(entity.getStorageLocationId()).map(l -> l.getName()).orElse(null), entity.getStorageBinId(),
            entity.getStorageBinId() == null ? null : bins.findByIdAndIsDeletedFalse(entity.getStorageBinId()).map(b -> b.getName()).orElse(null), document.getMaterialIds(), document.getMaterialGroupIds(),
            entity.getScopeMode(), document.getDepartmentIds(), document.getWarehouseIds());
        var blockers = new ArrayList<String>();
        if (entity.getSnapshotAt() != null && !entity.isFreezeOperations()) blockers.add("UNFROZEN_SNAPSHOT_REQUIRES_RESTART");
        if (entity.getUnidentifiedStock().stream().anyMatch(o -> o.resolvedLineId() == null)) blockers.add("UNIDENTIFIED_STOCK");
        if (lines.stream().anyMatch(l -> l.getCountedQuantity() == null)) blockers.add("UNCOUNTED_LINES");
        if (visible && lines.stream().anyMatch(l -> l.getCountedQuantity() != null && l.getDifferenceQuantity() != null && l.getDifferenceQuantity().signum() != 0 && l.getLineStatus() != com.enterprise.mm.enums.InventoryLineStatus.RESOLVED)) blockers.add("UNRESOLVED_DIFFERENCES");
        var scopeConflicts = new ArrayList<BlockingCount>();
        if (entity.getSnapshotAt() == null && entity.isFreezeOperations()) {
            for (var other : scope.active(entity.getCompanyId())) {
                if (entity.getId().equals(other.getId()) || !scope.overlaps(entity, other)) continue;
                if (!blockers.contains("INVENTORY_SCOPE_FROZEN")) blockers.add("INVENTORY_SCOPE_FROZEN");
                try {
                    access.requireRead(other);
                    scopeConflicts.add(new BlockingCount(other.getId(), other.getCode(), other.getName()));
                } catch (org.springframework.security.access.AccessDeniedException | com.enterprise.mm.error.NotFoundException hidden) {
                    // The freeze is actionable, but its owning document remains private.
                }
            }
        }
        boolean approved = "APPROVED".equals(entity.getStatus());
        var posting = visible && !Set.of("ADJUSTMENT_POSTED", "CLOSED", "CANCELLED").contains(entity.getStatus()) ? readiness.inspect(entity, entity.getPostingDate()) : null;
        if (approved && posting != null) posting.blockers().stream().filter(b -> !"POSTING_DATE_REQUIRED".equals(b)).filter(b -> !blockers.contains(b)).forEach(blockers::add);
        boolean canPost = approved && visible && access.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT");
        boolean canReturn = review && Set.of("WAITING_APPROVAL", "APPROVED", "REJECTED").contains(entity.getStatus());
        var capabilities = new Capabilities(canCount, review, visible, review && "WAITING_APPROVAL".equals(entity.getStatus()), canPost, canReturn, editable && access.canCommissionReview(entity), editable && review && access.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"), access.canPlan(entity));
        var actions = List.of(new Action("COUNT", canCount, canCount ? null : "COUNT_NOT_EDITABLE"),
            new Action("REVIEW", review, review ? null : "REVIEW_AUTHORITY_REQUIRED"), new Action("POST_ADJUSTMENTS", canPost && blockers.isEmpty(), canPost && blockers.isEmpty() ? null : "POSTING_NOT_READY"));
        return new Context(document, lines, capabilities, actions, blockers, freeze,
            visible ? lines.stream().map(InventoryItemResponse::getLinkedStockMovementId).filter(Objects::nonNull).toList() : List.of(), historyRows, entity.getCountRevision(), enabled, scopeConflicts, posting);
    }
}
