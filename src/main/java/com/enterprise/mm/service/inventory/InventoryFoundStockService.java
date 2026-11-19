package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.InventoryReviewDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class InventoryFoundStockService {
    private final InventoryAccessService access;
    private final InventoryRevisionService revisions;
    private final InventoryEvidenceService evidence;
    private final InventoryScopeService scope;
    private final InventoryScopeLockService locks;
    private final InventoryItemRepository items;
    private final InventoryDocumentRepository documents;
    private final StockBatchRepository batches;
    private final MaterialRepository materials;
    private final MaterialMasterRequestRepository masterRequests;
    private final WarehouseRepository warehouses;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final MaterialCatalogGuard catalog;
    private final RoleDataScopeService authority;
    private final InventoryItemMapper mapper;
    private final InventoryLineHistoryService history;
    private final JdbcTemplate jdbc;

    public InventoryItemResponse add(UUID id, FoundStock c) {
        if (c == null || c.materialId() == null) throw new BadRequestException("Select a known material or record an unidentified observation");
        var d = editable(id, c.expectedDocumentVersion());
        // Company gate first: serial ownership can span warehouses and owned transit.
        locks.lock(d.getCompanyId(), List.of());
        validatePosition(d, c.warehouseId(), c.storageLocationId(), c.storageBinId(), c.materialId());
        var material = materials.findByIdAndIsDeletedFalse(c.materialId()).filter(m -> scoped(d, m)).orElseThrow(() -> new BadRequestException("Material is outside this company"));
        catalog.requireActiveTmbMaterial(material.getId(), "materialId");
        String batchNumber = identity(c.batchNumber()), serial = identity(c.serialNumber()); var expiry = c.expiryDate();
        StockBatch batch = null;
        if (c.stockBatchId() != null) {
            batch = batches.findByIdAndIsDeletedFalse(c.stockBatchId()).filter(b -> scoped(d, b) && material.getId().equals(b.getMaterialId()) && c.warehouseId().equals(b.getWarehouseId())).orElseThrow(() -> new BadRequestException("Batch does not belong to this material and warehouse"));
            if (batchNumber != null && !batchNumber.equals(batch.getBatchNumber()) || serial != null && !serial.equals(batch.getSerialNumber()) || expiry != null && !expiry.equals(batch.getExpirationDate())) throw new BadRequestException("Observed identity must match the selected batch");
            batchNumber = batch.getBatchNumber(); serial = batch.getSerialNumber(); expiry = batch.getExpirationDate();
        } else if (batchNumber != null) {
            String number = batchNumber;
            if (batches.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByReceivedDateAscCreatedAtAsc(d.getTenantId(), d.getCompanyId(), c.warehouseId()).stream().anyMatch(b -> material.getId().equals(b.getMaterialId()) && number.equals(b.getBatchNumber())))
                throw new ConflictException("This batch already exists. Select it to preserve its stock identity.", "FOUND_STOCK_BATCH_EXISTS");
        }
        InventoryCountMutationService.validateQuantity(c.countedQuantity(), false, serial == null ? StockUomPolicy.quantityScale(material) : 0, serial != null);
        if (c.countedQuantity().signum() <= 0) throw new BadRequestException("Found stock requires a positive observed quantity");
        String reason = InventoryReviewService.reason(c.reason()); var files = evidence.validate(d, null, c.evidenceIds(), true);
        for (var line : lines(d)) if (sameKey(line, c) && (c.stockBatchId() != null || Objects.equals(line.getObservedBatchNumber(), batchNumber) && Objects.equals(line.getObservedSerialNumber(), serial)))
            throw new ConflictException("This stock position is already in the count. Enter its observation on the existing line.", "FOUND_STOCK_KEY_EXISTS");
        if (serial != null) requireSerialAvailable(d, serial, null);
        InventoryUnidentifiedObservation unidentified = null;
        if (c.materialMasterRequestId() != null) {
            var request = request(d, c.materialMasterRequestId());
            if (request.getAppliedAt() == null || !(material.getId().equals(request.getMaterialId()) || material.getId().equals(request.getTargetMaterialId()))) throw new BadRequestException("The material-master request must be applied to this material first");
            unidentified = d.getUnidentifiedStock().stream().filter(o -> c.materialMasterRequestId().equals(o.materialMasterRequestId()) && o.resolvedLineId() == null).findFirst().orElseThrow(() -> new BadRequestException("No unresolved observation is linked to this request"));
            if (!Objects.equals(unidentified.warehouseId(), c.warehouseId()) || !Objects.equals(unidentified.storageLocationId(), c.storageLocationId()) || !Objects.equals(unidentified.storageBinId(), c.storageBinId())) throw new BadRequestException("Resolve unidentified stock in its observed location");
        }
        var line = new InventoryItem(); line.setTenantId(d.getTenantId()); line.setCompanyId(d.getCompanyId()); line.setInventoryDocumentId(id);
        line.setMaterialId(material.getId()); line.setMaterial(material); line.setWarehouseId(c.warehouseId()); line.setStorageLocationId(c.storageLocationId()); line.setStorageBinId(c.storageBinId()); line.setStockBatchId(c.stockBatchId()); line.setStockBatch(batch);
        line.setObservedBatchNumber(batchNumber); line.setObservedSerialNumber(serial); line.setObservedExpiryDate(expiry);
        line.setSystemQuantity(BigDecimal.ZERO); line.setCountedQuantity(c.countedQuantity()); line.setDifferenceQuantity(c.countedQuantity()); line.setUnitCost(BigDecimal.ZERO); line.setDifferenceAmount(BigDecimal.ZERO);
        line.setCountRevision(revisions.invalidate(id, "Found stock recorded")); line.setCountedBy(access.username()); line.setCountedAt(LocalDateTime.now());
        line.setFoundStock(true); line.setFoundReason(reason); line.setFoundEvidenceIds(files); line.setLineStatus(InventoryLineStatus.DISCREPANCY); line.setStatus(line.getLineStatus().name()); line.setDiscrepancyType(InventoryDiscrepancyType.SURPLUS); line.setExplanationStatus(InventoryExplanationStatus.NOT_REQUIRED);
        items.saveAndFlush(line);
        if (unidentified != null) { var original = unidentified; d.setUnidentifiedStock(d.getUnidentifiedStock().stream().map(o -> o.id().equals(original.id()) ? o.resolve(line.getId()) : o).toList()); }
        d.setStatus(InventoryStatus.IN_PROGRESS.name()); updateTotals(d); documents.saveAndFlush(d);
        history.record("FOUND_STOCK", null, line, reason, access.username());
        return InventoryReadPolicy.project(mapper.toResponse(line), access.canSeeSystemQuantity(d));
    }

    public InventoryUnidentifiedObservation unidentified(UUID id, UnidentifiedStock c) {
        if (c == null) throw new BadRequestException("An observed identity and material-master request are required");
        var d = editable(id, c.expectedDocumentVersion()); scope.lock(d);
        validatePosition(d, c.warehouseId(), c.storageLocationId(), c.storageBinId(), null); request(d, c.materialMasterRequestId());
        InventoryCountMutationService.validateQuantity(c.countedQuantity(), false, 4, false);
        if (c.countedQuantity().signum() <= 0) throw new BadRequestException("Found stock requires a positive observed quantity");
        if (d.getUnidentifiedStock().stream().anyMatch(o -> c.materialMasterRequestId().equals(o.materialMasterRequestId()) && o.resolvedLineId() == null)) throw new BadRequestException("This request already has an unresolved observation");
        var observation = new InventoryUnidentifiedObservation(UUID.randomUUID(), c.materialMasterRequestId(), InventoryReviewService.reason(c.observedName()), c.warehouseId(), c.storageLocationId(), c.storageBinId(), c.countedQuantity(), InventoryReviewService.reason(c.reason()), evidence.validate(d, null, c.evidenceIds(), true), access.username(), LocalDateTime.now(), null);
        revisions.invalidate(id, "Unidentified stock recorded"); var observations = new ArrayList<>(d.getUnidentifiedStock()); observations.add(observation); d.setUnidentifiedStock(observations); d.setStatus(InventoryStatus.IN_PROGRESS.name()); documents.saveAndFlush(d); return observation;
    }
    /** Caller holds the company scope gate; repeat immediately before creating a found serial's batch. */
    public void requireSerialAvailable(InventoryDocument d, String serial, UUID ownLineId) {
        boolean existing = batches.findByTenantIdAndCompanyIdAndQuantityGreaterThanAndIsDeletedFalse(d.getTenantId(), d.getCompanyId(), BigDecimal.ZERO).stream().anyMatch(b -> serial.equals(b.getSerialNumber()));
        Long transit = jdbc.queryForObject("""
            select count(*) from transfer_shipment_allocations a where a.tenant_id=? and a.company_id=? and a.serial_number=?
            and a.dispatched_quantity > coalesce((select sum(c.quantity) from transfer_allocation_clearances c where c.allocation_id=a.id),0)
            """, Long.class, d.getTenantId(), d.getCompanyId(), serial);
        Long observed = jdbc.queryForObject("""
            select count(*) from inventory_items i join inventory_documents d on d.id=i.inventory_document_id
            where i.tenant_id=? and i.company_id=? and i.is_delete=false and d.is_delete=false and i.found_stock=true
            and i.observed_serial_number=? and i.counted_quantity>0 and d.status not in ('CANCELLED','CLOSED','ADJUSTMENT_POSTED')
            and (?::uuid is null or i.id<>?::uuid)
            """, Long.class, d.getTenantId(), d.getCompanyId(), serial, ownLineId, ownLineId);
        if (existing || transit != null && transit > 0 || observed != null && observed > 0) throw new ConflictException("This serial is already owned in stock, transit, or another active found-stock observation.", "FOUND_STOCK_SERIAL_OWNED");
    }
    private InventoryDocument editable(UUID id, Long version) {
        var d = revisions.lock(id); access.requireCount(d); InventoryRevisionService.requireEditable(d);
        if (!d.isFreezeOperations()) throw new BusinessException("Found stock requires a frozen snapshot");
        if (!Objects.equals(d.getVersion(), version)) throw new ConflictException("This count changed. Refresh before adding stock.", "DOCUMENT_VERSION_CONFLICT"); return d;
    }
    private void validatePosition(InventoryDocument d, UUID warehouse, UUID location, UUID bin, UUID material) {
        if (warehouse == null || !authority.canAccessWarehouse(warehouse) || !scope.contains(d, warehouse, location, bin, material)) throw new BadRequestException("The observed stock position is outside the authorized count scope");
        warehouses.findByIdAndIsDeletedFalse(warehouse).filter(w -> scoped(d, w)).orElseThrow(() -> new BadRequestException("Warehouse is outside this company"));
        if (location != null) locations.findByIdAndIsDeletedFalse(location).filter(l -> scoped(d, l) && warehouse.equals(l.getWarehouseId())).orElseThrow(() -> new BadRequestException("Location is outside this warehouse"));
        if (bin != null) bins.findByIdAndIsDeletedFalse(bin).filter(b -> scoped(d, b) && warehouse.equals(b.getWarehouseId()) && location != null && location.equals(b.getStorageLocationId())).orElseThrow(() -> new BadRequestException("Bin requires its parent location"));
    }
    private MaterialMasterRequest request(InventoryDocument d, UUID id) {
        if (id == null) throw new BadRequestException("Link a material-master request for unidentified stock");
        return masterRequests.findByIdAndIsDeletedFalse(id).filter(r -> scoped(d, r)).orElseThrow(() -> new BadRequestException("Material-master request is outside this company"));
    }
    private List<InventoryItem> lines(InventoryDocument d) { return items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), d.getId()); }
    private boolean sameKey(InventoryItem l, FoundStock c) { return Objects.equals(l.getMaterialId(), c.materialId()) && Objects.equals(l.getWarehouseId(), c.warehouseId()) && Objects.equals(l.getStorageLocationId(), c.storageLocationId()) && Objects.equals(l.getStorageBinId(), c.storageBinId()) && Objects.equals(l.getStockBatchId(), c.stockBatchId()); }
    private boolean scoped(InventoryDocument d, BusinessEntity e) { return d.getTenantId().equals(e.getTenantId()) && d.getCompanyId().equals(e.getCompanyId()); }
    private String identity(String text) { if (text == null || text.isBlank()) return null; if (text.trim().length() > 120) throw new BadRequestException("Batch and serial identities may contain up to 120 characters"); return text.trim(); }
    private void updateTotals(InventoryDocument d) {
        d.setTotalItems((int) items.countByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(d.getTenantId(), d.getId()));
        d.setCountedItems((int) items.countByTenantIdAndInventoryDocumentIdAndCountedQuantityIsNotNullAndIsDeletedFalse(d.getTenantId(), d.getId()));
        d.setDiscrepancyItems((int) items.countByTenantIdAndInventoryDocumentIdAndDifferenceQuantityNotAndIsDeletedFalse(d.getTenantId(), d.getId(), BigDecimal.ZERO));
    }
}
