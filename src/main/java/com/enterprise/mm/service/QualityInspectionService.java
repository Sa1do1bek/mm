package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.QualityControlDtos.QualityDecisionRequest;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.QualityInspectionUpdateRequest;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.payload.QualityInspectionSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.QualityInspectionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.QualityInspectionType;
import com.enterprise.mm.enums.LaboratoryTestStatus;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.LaboratoryTestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.quality.workspace.service.IncomingQualityActionPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QualityInspectionService {
    private final InventoryFreezeGuardService inventoryFreezeGuard;
    private final String entityName = "QualityInspection";
    private final QualityInspectionRepository repository;
    private final LaboratoryTestRepository laboratoryTestRepository;
    private final QualityInspectionMapper mapper;
    private final QualityControlGuard guard;
    private final StockBalanceRepository stockBalanceRepository;
    private final QualityQuarantineLotService quarantineLotService;
    private final SupplierEvaluationService supplierEvaluationService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final IncomingQualityActionPolicy incomingQualityActionPolicy;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final com.enterprise.mm.repository.GoodsReceiptBatchRepository goodsReceiptBatchRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ProductionUnitService productionUnits;

    protected void beforeCreate(QualityInspection entity, QualityInspectionCreateRequest dto) {
        normalize(entity);
        hydrateFromStockMovement(entity);
        validateLinks(entity);
        StockMovement source = guard.stockMovement(entity.getStockMovementId());
        if (source != null && "PRODUCTION_OPERATION".equals(source.getReferenceType())) {
            if (source.getMovementType() != com.enterprise.mm.enums.MovementType.RECEIPT || !"PRODUCTION".equals(entity.getInspectionType())
                || entity.getReceivedQuantity().compareTo(source.getQuantity()) != 0
                || !repository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), source.getId()).isEmpty())
                throw new BusinessException("Production output requires one inspection for its entire receipt");
        } else if ("PRODUCTION".equals(entity.getInspectionType())) {
            throw new BusinessException("Production inspection requires a posted production output");
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        if (entity.getInspectionNumber() == null || entity.getInspectionNumber().isBlank()) {
            entity.setInspectionNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(QualityInspectionStatus.DRAFT.name());
    }
    protected void beforeUpdate(QualityInspection entity, QualityInspectionUpdateRequest dto) {
        if (!QualityInspectionStatus.DRAFT.name().equals(entity.getStatus()) && !QualityInspectionStatus.SAMPLING.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or SAMPLING quality inspection can be edited");
        }
        normalize(entity);
        hydrateFromStockMovement(entity);
        validateLinks(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public QualityInspectionResponse startQuarantine(UUID id) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.QUARANTINE);
        BigDecimal quantity = positive(inspection.getQuarantinedQuantity()).signum() > 0
            ? positive(inspection.getQuarantinedQuantity())
            : positive(inspection.getReceivedQuantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Quarantine quantity must be greater than zero");
        }
        holdStock(inspection, quantity);
        productionUnits.quality(guard.stockMovement(inspection.getStockMovementId()), id, false, BigDecimal.ZERO, BigDecimal.ZERO);
        inspection.setQuarantinedQuantity(quantity);
        inspection.setStatus(QualityInspectionStatus.IN_QUARANTINE.name());
        inspection.setQuarantineStartedAt(LocalDateTime.now());
        auditService.log("QualityInspection", id, "QUARANTINE", quantity.toPlainString(), null, null);
        QualityInspection saved = repository.saveAndFlush(inspection);
        quarantineLotService.createForInspection(saved, quantity, "QC inspection quarantine");
        return mapper.toResponse(saved);
    }
    @Transactional
    public QualityInspectionResponse startSampling(UUID id) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.SAMPLING);
        inspection.setStatus(QualityInspectionStatus.SAMPLING.name());
        inspection.setInspectionStartedAt(LocalDateTime.now());
        auditService.log("QualityInspection", id, "SAMPLING", inspection.getInspectionNumber(), null, null);
        return mapper.toResponse(repository.save(inspection));
    }
    @Transactional
    public QualityInspectionResponse sendToLab(UUID id) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.SEND_TO_LAB);
        inspection.setStatus(QualityInspectionStatus.LAB_TESTING.name());
        if (inspection.getInspectionStartedAt() == null) {
            inspection.setInspectionStartedAt(LocalDateTime.now());
        }
        auditService.log("QualityInspection", id, "SEND_TO_LAB", inspection.getInspectionNumber(), null, null);
        return mapper.toResponse(repository.save(inspection));
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public QualityInspectionResponse release(UUID id, QualityDecisionRequest request) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.RELEASE);
        BigDecimal accepted = request == null || request.acceptedQuantity() == null
            ? positive(inspection.getReceivedQuantity()).subtract(positive(inspection.getRejectedQuantity()))
            : positive(request.acceptedQuantity());
        BigDecimal rejected = request == null || request.rejectedQuantity() == null ? positive(inspection.getRejectedQuantity()) : positive(request.rejectedQuantity());
        if (request != null && ((request.acceptedQuantity() != null && request.acceptedQuantity().signum() < 0)
            || (request.rejectedQuantity() != null && request.rejectedQuantity().signum() < 0)))
            throw new BusinessException("Quality decision quantities cannot be negative");
        StockUomPolicy.quantity(guard.material(inspection.getMaterialId()), accepted, true);
        StockUomPolicy.quantity(guard.material(inspection.getMaterialId()), rejected, true);
        validateDecision(inspection, accepted, rejected);
        productionUnits.quality(guard.stockMovement(inspection.getStockMovementId()), id, true, accepted, rejected);
        releaseStock(inspection, accepted);
        quarantineLotService.releaseForInspection(inspection, accepted, request == null ? null : request.comment());
        if (rejected.signum() > 0) quarantineLotService.rejectForInspection(inspection, rejected, request == null ? null : request.comment());
        inspection.setAcceptedQuantity(accepted);
        inspection.setRejectedQuantity(rejected);
        inspection.setQuarantinedQuantity(positive(inspection.getQuarantinedQuantity()).subtract(accepted).max(BigDecimal.ZERO));
        inspection.setStatus(QualityInspectionStatus.RELEASED.name());
        inspection.setQuarantineReleasedAt(LocalDateTime.now());
        inspection.setCompletedAt(LocalDateTime.now());
        inspection.setConclusion(request == null ? inspection.getConclusion() : request.comment());
        auditService.log("QualityInspection", id, "RELEASE", accepted.toPlainString(), null, null);
        QualityInspection saved = repository.save(inspection);
        syncReceiptQualityStatus(saved, accepted, rejected);
        supplierEvaluationService.recalculateFromQualityInspection(saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public QualityInspectionResponse reject(UUID id, QualityDecisionRequest request) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.REJECT);
        BigDecimal rejected = request == null || request.rejectedQuantity() == null ? positive(inspection.getReceivedQuantity()) : positive(request.rejectedQuantity());
        BigDecimal accepted = request == null || request.acceptedQuantity() == null ? BigDecimal.ZERO : positive(request.acceptedQuantity());
        if (request != null && ((request.acceptedQuantity() != null && request.acceptedQuantity().signum() < 0)
            || (request.rejectedQuantity() != null && request.rejectedQuantity().signum() < 0)))
            throw new BusinessException("Quality decision quantities cannot be negative");
        StockUomPolicy.quantity(guard.material(inspection.getMaterialId()), accepted, true);
        StockUomPolicy.quantity(guard.material(inspection.getMaterialId()), rejected, true);
        validateDecision(inspection, accepted, rejected);
        productionUnits.quality(guard.stockMovement(inspection.getStockMovementId()), id, true, accepted, rejected);
        if (accepted.signum() > 0) {
            releaseStock(inspection, accepted);
            quarantineLotService.releaseForInspection(inspection, accepted, request == null ? null : request.comment());
            inspection.setQuarantinedQuantity(positive(inspection.getQuarantinedQuantity()).subtract(accepted).max(BigDecimal.ZERO));
        }
        quarantineLotService.rejectForInspection(inspection, rejected, request == null ? null : request.comment());
        inspection.setAcceptedQuantity(accepted);
        inspection.setRejectedQuantity(rejected);
        inspection.setStatus(QualityInspectionStatus.REJECTED.name());
        inspection.setCompletedAt(LocalDateTime.now());
        inspection.setConclusion(request == null ? inspection.getConclusion() : request.comment());
        auditService.log("QualityInspection", id, "REJECT", rejected.toPlainString(), null, null);
        QualityInspection saved = repository.save(inspection);
        syncReceiptQualityStatus(saved, accepted, rejected);
        supplierEvaluationService.recalculateFromQualityInspection(saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public QualityInspectionResponse cancel(UUID id) {
        QualityInspection inspection = findEntityForUpdate(id);
        incomingQualityActionPolicy.requireStateAllowed(
            inspection, IncomingQualityActionPolicy.CANCEL);
        BigDecimal held = positive(inspection.getQuarantinedQuantity());
        if (held.signum() > 0) throw new BusinessException("Held stock requires an explicit quality decision; cancellation cannot release it");
        inspection.setStatus(QualityInspectionStatus.CANCELLED.name());
        auditService.log("QualityInspection", id, "CANCEL", inspection.getInspectionNumber(), null, null);
        return mapper.toResponse(repository.save(inspection));
    }
    @Transactional
    public void reconcileLaboratoryStatus(UUID id, String failureComment) {
        QualityInspection inspection = findEntityForUpdate(id);
        if (QualityInspectionStatus.RELEASED.name().equals(inspection.getStatus())
            || QualityInspectionStatus.REJECTED.name().equals(inspection.getStatus())
            || QualityInspectionStatus.CANCELLED.name().equals(inspection.getStatus())) {
            return;
        }
        var tests = laboratoryTestRepository
            .findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), inspection.getCompanyId(), inspection.getId());
        boolean anyFailed = tests.stream()
            .anyMatch(test -> LaboratoryTestStatus.FAILED.name().equals(test.getStatus()));
        boolean allPassed = !tests.isEmpty() && tests.stream()
            .allMatch(test -> LaboratoryTestStatus.PASSED.name().equals(test.getStatus()));
        if (anyFailed) {
            inspection.setStatus(QualityInspectionStatus.FAILED.name());
            if (failureComment != null && !failureComment.isBlank()) {
                inspection.setConclusion(failureComment);
            }
            repository.save(inspection);
        } else if (allPassed) {
            inspection.setStatus(QualityInspectionStatus.PASSED.name());
            repository.save(inspection);
        }
    }

    @Transactional
    public void markLabFailed(UUID id, String comment) {
        reconcileLaboratoryStatus(id, comment);
    }
    private void normalize(QualityInspection entity) {
        entity.setInspectionType(normalizeCode(entity.getInspectionType(), QualityInspectionType.INCOMING.name(), enumNames(QualityInspectionType.values()), "inspectionType"));
        if (entity.getInspectionDate() == null) {
            entity.setInspectionDate(LocalDate.now());
        }
        if (entity.getInspectionNumber() != null) {
            entity.setInspectionNumber(entity.getInspectionNumber().trim());
        }
        entity.setReceivedQuantity(positive(entity.getReceivedQuantity()));
        entity.setSampleQuantity(positive(entity.getSampleQuantity()));
        entity.setAcceptedQuantity(positive(entity.getAcceptedQuantity()));
        entity.setRejectedQuantity(positive(entity.getRejectedQuantity()));
        entity.setQuarantinedQuantity(positive(entity.getQuarantinedQuantity()));
    }
    private void hydrateFromStockMovement(QualityInspection entity) {
        StockMovement movement = guard.stockMovement(entity.getStockMovementId());
        if (movement == null) {
            return;
        }
        if (!StockMovementStatus.isEffective(movement.getStatus())) {
            throw new BusinessException("Quality inspection can be linked only to APPROVED or POSTED stock movement");
        }
        entity.setMaterialId(movement.getMaterialId());
        entity.setWarehouseId(movement.getWarehouseId());
        entity.setStorageLocationId(movement.getStorageLocationId());
        entity.setStockBatchId(movement.getStockBatchId());
        if (entity.getReceivedQuantity().signum() == 0) {
            entity.setReceivedQuantity(positive(movement.getQuantity()));
        }
    }
    private void validateLinks(QualityInspection entity) {
        guard.material(entity.getMaterialId());
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        guard.warehouse(entity.getWarehouseId());
        var supplier = guard.supplier(entity.getSupplierId());
        var po = guard.purchaseOrder(entity.getPurchaseOrderId());
        var item = guard.purchaseOrderItem(entity.getPurchaseOrderItemId());
        if (po != null && supplier != null && !po.getSupplierId().equals(supplier.getId())) {
            throw new BusinessException("Quality inspection supplier must match purchase order supplier");
        }
        if (item != null && !item.getMaterialId().equals(entity.getMaterialId())) {
            throw new BusinessException("Quality inspection material must match purchase order item material");
        }
        if (entity.getReceivedQuantity().signum() <= 0) {
            throw new BusinessException("receivedQuantity must be greater than zero");
        }
    }
    private void ensureUnique(QualityInspection entity) {
        if (entity.getInspectionNumber() == null || entity.getInspectionNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndInspectionNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getInspectionNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Quality inspection number already exists");
            });
    }
    private void holdStock(QualityInspection inspection, BigDecimal quantity) {
        StockBalance balance = balance(inspection);
        if (positive(balance.getAvailableQuantity()).compareTo(quantity) < 0) {
            throw new BusinessException("Not enough available stock for quarantine");
        }
        balance.setAvailableQuantity(positive(balance.getAvailableQuantity()).subtract(quantity));
        balance.setQuarantineQuantity(positive(balance.getQuarantineQuantity()).add(quantity));
        stockBalanceRepository.save(balance);
    }
    private void releaseStock(QualityInspection inspection, BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            return;
        }
        StockBalance balance = balance(inspection);
        if (positive(balance.getQuarantineQuantity()).compareTo(quantity) < 0) {
            throw new BusinessException("Not enough quarantined stock to release");
        }
        balance.setQuarantineQuantity(positive(balance.getQuarantineQuantity()).subtract(quantity));
        balance.setAvailableQuantity(positive(balance.getAvailableQuantity()).add(quantity));
        stockBalanceRepository.save(balance);
    }
    private StockBalance balance(QualityInspection inspection) {
        // The posted receipt is the authority for the exact bin; inspection rows retain its lineage.
        StockMovement movement = guard.stockMovement(inspection.getStockMovementId());
        UUID storageBinId = movement == null ? null : movement.getStorageBinId();
        inventoryFreezeGuard.ensureStockKeyAllowed(inspection.getCompanyId(),inspection.getWarehouseId(),inspection.getStorageLocationId(),storageBinId,inspection.getMaterialId());
        return stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            inspection.getMaterialId(),
            inspection.getWarehouseId(),
            inspection.getStorageLocationId() == null,
            inspection.getStorageLocationId(),
            storageBinId == null,
            storageBinId,
            inspection.getStockBatchId() == null,
            inspection.getStockBatchId()
        ).orElseThrow(() -> new BusinessException("Stock balance not found for quality inspection"));
    }
    private void validateDecision(QualityInspection inspection, BigDecimal accepted, BigDecimal rejected) {
        if (accepted.signum() < 0 || rejected.signum() < 0) {
            throw new BusinessException("Accepted and rejected quantities cannot be negative");
        }
        if (accepted.add(rejected).compareTo(positive(inspection.getReceivedQuantity())) != 0) {
            throw new BusinessException(
                "Accepted plus rejected quantities must fully allocate received quantity");
        }
    }

    @Transactional(readOnly = true)
    public boolean hasPendingInspection(UUID purchaseOrderId, UUID itemId) {
        if (purchaseOrderId == null || itemId == null) return false;
        return repository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderId).stream()
            .anyMatch(q -> itemId.equals(q.getPurchaseOrderItemId()) && pending(q));
    }

    private boolean pending(QualityInspection inspection) {
        return !List.of("RELEASED", "REJECTED", "CANCELLED").contains(inspection.getStatus());
    }

    private void syncReceiptQualityStatus(QualityInspection inspection, BigDecimal accepted, BigDecimal rejected) {
        String qualityStatus = accepted.signum() > 0 && rejected.signum() > 0
            ? "ACCEPTED_WITH_DEVIATION"
            : accepted.signum() > 0 ? QualityInspectionStatus.RELEASED.name() : QualityInspectionStatus.REJECTED.name();
        if (inspection.getPurchaseOrderItemId() != null) {
            purchaseOrderItemRepository.findByIdForUpdate(inspection.getPurchaseOrderItemId())
                .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
                .filter(item -> inspection.getCompanyId().equals(item.getCompanyId()))
                .ifPresent(item -> {
                    item.setAcceptedQuantity(positive(item.getAcceptedQuantity()).add(accepted));
                    item.setRejectedQuantity(positive(item.getRejectedQuantity()).add(rejected));
                    item.setQualityStatus(hasPendingInspection(inspection.getPurchaseOrderId(), item.getId()) ? "PENDING_INSPECTION"
                        : decisionStatus(positive(item.getAcceptedQuantity()), positive(item.getRejectedQuantity()).add(positive(item.getDamagedQuantity()))));
                    purchaseOrderItemRepository.save(item);
                });
        }
        List<GoodsReceiptLine> lines = inspection.getStockMovementId() == null
            ? List.of()
            : goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), inspection.getCompanyId(), inspection.getStockMovementId());
        if (lines.isEmpty() && inspection.getStockBatchId() != null) {
            lines = goodsReceiptBatchRepository.findByTenantIdAndCompanyIdAndStockBatchIdAndIsDeletedFalse(
                TenantContext.currentTenant(), inspection.getCompanyId(), inspection.getStockBatchId()).stream()
                .map(batch -> goodsReceiptLineRepository.findById(batch.getGoodsReceiptLineId()).orElseThrow()).distinct().toList();
        }
        for (GoodsReceiptLine line : lines) {
            var batches = goodsReceiptBatchRepository.findByTenantIdAndCompanyIdAndGoodsReceiptLineIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), inspection.getCompanyId(), line.getId());
            var receiptBatchIds = batches.stream().map(com.enterprise.mm.entity.GoodsReceiptBatch::getStockBatchId).toList();
            var batchInspections = receiptBatchIds.isEmpty() ? List.<QualityInspection>of() : repository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), inspection.getCompanyId(), inspection.getPurchaseOrderId())
                .stream().filter(q -> receiptBatchIds.contains(q.getStockBatchId())).toList();
            if (!batchInspections.isEmpty()) {
                line.setQualityStatus(batchInspections.stream().anyMatch(this::pending) ? "PENDING_INSPECTION"
                    : decisionStatus(batchInspections.stream().map(q -> positive(q.getAcceptedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add),
                        positive(line.getRejectedQuantity()).add(positive(line.getDamagedQuantity())).add(
                            batchInspections.stream().map(q -> positive(q.getRejectedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add))));
                continue;
            }
            if (line.getAcceptedQuantity() != null && line.getAcceptedQuantity().signum() > 0) {
                line.setQualityStatus(qualityStatus);
            } else if (QualityInspectionStatus.REJECTED.name().equals(qualityStatus)) {
                line.setQualityStatus(qualityStatus);
            }
        }
        if (!lines.isEmpty()) {
            goodsReceiptLineRepository.saveAll(lines);
        }
    }
    private String decisionStatus(BigDecimal accepted, BigDecimal rejected) {
        return accepted.signum() > 0 ? (rejected.signum() > 0 ? "ACCEPTED_WITH_DEVIATION" : "RELEASED") : "REJECTED";
    }

    private boolean isAcceptedQualityStatus(String value) {
        String normalized = value == null || value.isBlank()
            ? null
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized == null) {
            return false;
        }
        return List.of("ACCEPTED", "ACCEPTED_WITH_DEVIATION", "RELEASED", "PASSED", "NOT_REQUIRED")
            .contains(normalized);
    }
    private String normalizeCode(String value, String fallback, String[] allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (String item : allowed) {
            if (item.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    private BigDecimal positive(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<QualityInspectionResponse> search(QualityInspectionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.supplierId(),
            filter.purchaseOrderId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public QualityInspectionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public QualityInspectionResponse create(QualityInspectionCreateRequest dto) {
        QualityInspection entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        QualityInspection saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public QualityInspectionResponse update(UUID id, QualityInspectionUpdateRequest dto) {
        QualityInspection entity = findEntityForUpdate(id);
        requireEditableSource(entity);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        requireEditableSource(entity);
        beforeUpdate(entity, dto);
        QualityInspection saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        QualityInspection entity = findEntityForUpdate(id);
        requireEditableSource(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public QualityInspectionResponse restore(UUID id) {
        QualityInspection entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        QualityInspection saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected QualityInspection findEntity(UUID id) {
        QualityInspection entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }

    public QualityInspection findEntityForUpdate(UUID id) {
        QualityInspection inspection = repository.findForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            id
        ).orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        roleDataScopeService.assertWarehouseAccess(inspection.getWarehouseId());
        return inspection;
    }
    private void requireEditableSource(QualityInspection inspection) {
        StockMovement movement = guard.stockMovement(inspection.getStockMovementId());
        if (movement != null && "PRODUCTION_OPERATION".equals(movement.getReferenceType()))
            throw new BusinessException("Production inspection evidence cannot be edited or deleted; use its quality actions");
    }

    void assertProductionReversalAllowed(UUID movementId) {
        for (var found : repository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), movementId)) {
            QualityInspection inspection = findEntityForUpdate(found.getId());
            if (!"RELEASED".equals(inspection.getStatus())
                || positive(inspection.getQuarantinedQuantity()).signum() != 0
                || positive(inspection.getAcceptedQuantity()).compareTo(inspection.getReceivedQuantity()) != 0)
                throw new BusinessException("Production output inspection must be fully released before reversal");
        }
    }
    protected boolean companyScoped() {
        return true;
    }
}
