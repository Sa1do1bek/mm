package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.QualityControlDtos.QualityQuarantineDecisionRequest;
import com.enterprise.mm.payload.QualityQuarantineLotResponse;
import com.enterprise.mm.payload.QualityQuarantineLotUpdateRequest;
import com.enterprise.mm.payload.QualityQuarantineLotCreateRequest;
import com.enterprise.mm.payload.QualityQuarantineLotSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.QualityQuarantineLotMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.QualityDisposition;
import com.enterprise.mm.enums.QualityQuarantineStatus;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.QualityQuarantineLotRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QualityQuarantineLotService {
    private final InventoryFreezeGuardService inventoryFreezeGuard;
    private final String entityName = "QualityQuarantineLot";
    private final QualityQuarantineLotRepository repository;
    private final QualityQuarantineLotMapper mapper;
    private final QualityControlGuard guard;
    private final StockBalanceRepository stockBalanceRepository;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    protected void beforeCreate(QualityQuarantineLot entity, QualityQuarantineLotCreateRequest dto) {
        hydrateFromInspection(entity);
        normalize(entity);
        validate(entity);
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

        if (entity.getQuarantineNumber() == null || entity.getQuarantineNumber().isBlank()) {
            entity.setQuarantineNumber(entity.getCode());
        }
        ensureUnique(entity);
        ensureNoActiveDuplicate(entity);
        entity.setStatus(QualityQuarantineStatus.QUARANTINED.name());
        if (entity.getQuarantineStartedAt() == null) {
            entity.setQuarantineStartedAt(LocalDateTime.now());
        }
    }
    protected void beforeUpdate(QualityQuarantineLot entity, QualityQuarantineLotUpdateRequest dto) {
        if (QualityQuarantineStatus.RELEASED.name().equals(entity.getStatus())
            || QualityQuarantineStatus.CANCELLED.name().equals(entity.getStatus())) {
            throw new BusinessException("Released or cancelled quarantine lot cannot be edited");
        }
        hydrateFromInspection(entity);
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public void createForInspection(QualityInspection inspection, BigDecimal quantity, String reason) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        QualityQuarantineLot lot = currentLotForUpdate(inspection.getId());
        if (lot == null) {
            lot = new QualityQuarantineLot();
            lot.setCode("QCQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            lot.setName("Quarantine " + inspection.getInspectionNumber());
            lot.setStatus(QualityQuarantineStatus.QUARANTINED.name());
            lot.setQuarantineNumber(lot.getCode());
            lot.setQualityInspectionId(inspection.getId());
            copyInspectionFields(lot, inspection);
            lot.setQuarantinedQuantity(quantity);
            lot.setRemainingQuantity(quantity);
            lot.setReleasedQuantity(BigDecimal.ZERO);
            lot.setRejectedQuantity(BigDecimal.ZERO);
            lot.setDisposition(QualityDisposition.QUARANTINE.name());
            lot.setQuarantineReason(reason);
            lot.setQuarantineStartedAt(LocalDateTime.now());
        } else {
            lot.setQuarantinedQuantity(positive(lot.getQuarantinedQuantity()).add(quantity));
            lot.setRemainingQuantity(positive(lot.getRemainingQuantity()).add(quantity));
            lot.setStatus(QualityQuarantineStatus.QUARANTINED.name());
            lot.setQuarantineReason(reason == null ? lot.getQuarantineReason() : reason);
        }
        repository.save(lot);
        auditService.log("QualityQuarantineLot", lot.getId(), "CREATE_FOR_INSPECTION", quantity.toPlainString(), null, null);
    }
    @Transactional
    public void releaseForInspection(QualityInspection inspection, BigDecimal quantity, String comment) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        QualityQuarantineLot lot = requiredLotForUpdate(inspection.getId());
        releaseLotOnly(lot, quantity);
        repository.save(lot);
        auditService.log("QualityQuarantineLot", lot.getId(), "RELEASE_FOR_INSPECTION", comment, null, null);
    }
    @Transactional
    public void rejectForInspection(QualityInspection inspection, BigDecimal quantity, String comment) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        QualityQuarantineLot lot = requiredLotForUpdate(inspection.getId());
        rejectLotOnly(lot, quantity, QualityDisposition.QUARANTINE.name());
        repository.save(lot);
        auditService.log("QualityQuarantineLot", lot.getId(), "REJECT_FOR_INSPECTION", comment, null, null);
    }
    @Transactional
    public void cancelForInspection(QualityInspection inspection, String comment) {
        QualityQuarantineLot lot = currentLotForUpdate(inspection.getId());
        if (lot == null) {
            return;
        }
        lot.setStatus(QualityQuarantineStatus.CANCELLED.name());
        lot.setRemainingQuantity(BigDecimal.ZERO);
        lot.setClosedAt(LocalDateTime.now());
        repository.save(lot);
        auditService.log("QualityQuarantineLot", lot.getId(), "CANCEL_FOR_INSPECTION", comment, null, null);
    }
    @Transactional
    public void linkNonconformance(UUID inspectionId, UUID nonconformanceId) {
        QualityQuarantineLot lot = currentLotForUpdate(inspectionId);
        if (lot == null) {
            return;
        }
        lot.setQualityNonconformanceId(nonconformanceId);
        repository.save(lot);
        auditService.log("QualityQuarantineLot", lot.getId(), "LINK_NONCONFORMANCE", String.valueOf(nonconformanceId), null, null);
    }
    @Transactional
    public QualityQuarantineLotResponse release(UUID id, QualityQuarantineDecisionRequest request) {
        QualityQuarantineLot lot = findLotForUpdate(id);
        requirePurchasingLot(lot);
        BigDecimal quantity = request == null || request.quantity() == null ? positive(lot.getRemainingQuantity()) : positive(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Release quantity must be greater than zero");
        }
        releaseStock(lot, quantity);
        releaseLotOnly(lot, quantity);
        lot.setDisposition(QualityDisposition.RELEASE.name());
        auditService.log("QualityQuarantineLot", id, "RELEASE", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(lot));
    }
    @Transactional
    public QualityQuarantineLotResponse reject(UUID id, QualityQuarantineDecisionRequest request) {
        QualityQuarantineLot lot = findLotForUpdate(id);
        requirePurchasingLot(lot);
        BigDecimal quantity = request == null || request.quantity() == null ? positive(lot.getRemainingQuantity()) : positive(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Reject quantity must be greater than zero");
        }
        rejectLotOnly(lot, quantity, normalizeDisposition(request == null ? null : request.disposition(), QualityDisposition.RETURN_TO_SUPPLIER.name()));
        auditService.log("QualityQuarantineLot", id, "REJECT", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(lot));
    }
    @Transactional
    public QualityQuarantineLotResponse cancel(UUID id, QualityQuarantineDecisionRequest request) {
        QualityQuarantineLot lot = findLotForUpdate(id);
        requirePurchasingLot(lot);
        if (QualityQuarantineStatus.RELEASED.name().equals(lot.getStatus())) {
            throw new BusinessException("Released quarantine lot cannot be cancelled");
        }
        BigDecimal remaining = positive(lot.getRemainingQuantity());
        if (remaining.signum() > 0) {
            releaseStock(lot, remaining);
            lot.setRemainingQuantity(BigDecimal.ZERO);
        }
        lot.setStatus(QualityQuarantineStatus.CANCELLED.name());
        lot.setClosedAt(LocalDateTime.now());
        auditService.log("QualityQuarantineLot", id, "CANCEL", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(lot));
    }
    private void hydrateFromInspection(QualityQuarantineLot entity) {
        if (entity.getQualityInspectionId() == null) {
            throw new BusinessException("qualityInspectionId is required");
        }
        QualityInspection inspection = guard.inspection(entity.getQualityInspectionId());
        copyInspectionFields(entity, inspection);
        if (positive(entity.getQuarantinedQuantity()).signum() == 0) {
            BigDecimal quantity = positive(inspection.getQuarantinedQuantity()).signum() > 0
                ? positive(inspection.getQuarantinedQuantity())
                : positive(inspection.getReceivedQuantity());
            entity.setQuarantinedQuantity(quantity);
        }
    }
    private void copyInspectionFields(QualityQuarantineLot lot, QualityInspection inspection) {
        lot.setSupplierId(inspection.getSupplierId());
        lot.setPurchaseOrderId(inspection.getPurchaseOrderId());
        lot.setPurchaseOrderItemId(inspection.getPurchaseOrderItemId());
        lot.setStockMovementId(inspection.getStockMovementId());
        lot.setMaterialId(inspection.getMaterialId());
        lot.setWarehouseId(inspection.getWarehouseId());
        lot.setStorageLocationId(inspection.getStorageLocationId());
        lot.setStockBatchId(inspection.getStockBatchId());
    }
    private void normalize(QualityQuarantineLot entity) {
        entity.setQuarantinedQuantity(positive(entity.getQuarantinedQuantity()));
        entity.setReleasedQuantity(positive(entity.getReleasedQuantity()));
        entity.setRejectedQuantity(positive(entity.getRejectedQuantity()));
        entity.setRemainingQuantity(positive(entity.getRemainingQuantity()));
        if (entity.getRemainingQuantity().signum() == 0 && entity.getQuarantinedQuantity().signum() > 0) {
            entity.setRemainingQuantity(entity.getQuarantinedQuantity().subtract(entity.getReleasedQuantity()).max(BigDecimal.ZERO));
        }
        entity.setDisposition(normalizeDisposition(entity.getDisposition(), QualityDisposition.QUARANTINE.name()));
        if (entity.getQuarantineNumber() != null) {
            entity.setQuarantineNumber(entity.getQuarantineNumber().trim());
        }
    }
    private void validate(QualityQuarantineLot entity) {
        guard.material(entity.getMaterialId());
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        guard.warehouse(entity.getWarehouseId());
        if (entity.getQuarantinedQuantity().signum() <= 0) {
            throw new BusinessException("quarantinedQuantity must be greater than zero");
        }
        if (entity.getReleasedQuantity().compareTo(entity.getQuarantinedQuantity()) > 0) {
            throw new BusinessException("releasedQuantity cannot exceed quarantinedQuantity");
        }
        if (entity.getRemainingQuantity().add(entity.getReleasedQuantity()).compareTo(entity.getQuarantinedQuantity()) > 0) {
            throw new BusinessException("remainingQuantity plus releasedQuantity cannot exceed quarantinedQuantity");
        }
        if (entity.getRejectedQuantity().compareTo(entity.getQuarantinedQuantity()) > 0) {
            throw new BusinessException("rejectedQuantity cannot exceed quarantinedQuantity");
        }
    }
    private void requirePurchasingLot(QualityQuarantineLot lot) {
        if (lot.getQualityInspectionId() != null) throw new BusinessException("Resolve inspection quarantine through its quality inspection decision");
        if (lot.getTransferAllocationId() != null) throw new BusinessException("Resolve transfer quarantine through its transfer exception case");
    }
    private QualityQuarantineLot findLotForUpdate(UUID id) {
        return repository.findCompanyLotForUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Quality quarantine lot not found: " + id));
    }
    private QualityQuarantineLot requiredLotForUpdate(UUID inspectionId) {
        QualityQuarantineLot lot = currentLotForUpdate(inspectionId);
        if (lot == null) {
            throw new BusinessException("Quality quarantine lot not found for inspection");
        }
        return lot;
    }
    private QualityQuarantineLot currentLotForUpdate(UUID inspectionId) {
        return repository.findByInspectionForUpdate(TenantContext.currentTenant(), CompanyContext.currentCompany(), inspectionId)
            .stream()
            .filter(lot -> !QualityQuarantineStatus.CANCELLED.name().equals(lot.getStatus()))
            .max(Comparator.comparing(QualityQuarantineLot::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);
    }
    private void releaseLotOnly(QualityQuarantineLot lot, BigDecimal quantity) {
        ensureCanRelease(lot, quantity);
        lot.setReleasedQuantity(positive(lot.getReleasedQuantity()).add(quantity));
        lot.setRemainingQuantity(positive(lot.getRemainingQuantity()).subtract(quantity));
        lot.setReleasedAt(LocalDateTime.now());
        lot.setStatus(lot.getRemainingQuantity().signum() == 0
            ? QualityQuarantineStatus.RELEASED.name()
            : QualityQuarantineStatus.PARTIALLY_RELEASED.name());
        if (lot.getRemainingQuantity().signum() == 0) {
            lot.setClosedAt(LocalDateTime.now());
        }
    }
    private void rejectLotOnly(QualityQuarantineLot lot, BigDecimal quantity, String disposition) {
        BigDecimal unmarkedRemaining = positive(lot.getRemainingQuantity()).subtract(positive(lot.getRejectedQuantity())).max(BigDecimal.ZERO);
        if (unmarkedRemaining.compareTo(quantity) < 0) {
            throw new BusinessException("Reject quantity cannot exceed non-rejected quarantine quantity");
        }
        lot.setRejectedQuantity(positive(lot.getRejectedQuantity()).add(quantity));
        lot.setDisposition(disposition);
        lot.setRejectedAt(LocalDateTime.now());
        lot.setStatus(QualityQuarantineStatus.REJECTED.name());
    }
    private void ensureCanRelease(QualityQuarantineLot lot, BigDecimal quantity) {
        if (QualityQuarantineStatus.CANCELLED.name().equals(lot.getStatus())) {
            throw new BusinessException("Cancelled quarantine lot cannot be released");
        }
        BigDecimal releasable = positive(lot.getRemainingQuantity()).subtract(positive(lot.getRejectedQuantity())).max(BigDecimal.ZERO);
        if (releasable.compareTo(quantity) < 0) {
            throw new BusinessException("Release quantity cannot exceed non-rejected quarantine quantity");
        }
    }
    private void releaseStock(QualityQuarantineLot lot, BigDecimal quantity) {
        inventoryFreezeGuard.ensureStockKeyAllowed(lot.getCompanyId(),lot.getWarehouseId(),lot.getStorageLocationId(),null,lot.getMaterialId());
        StockBalance balance = stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            lot.getMaterialId(),
            lot.getWarehouseId(),
            lot.getStorageLocationId() == null,
            lot.getStorageLocationId(),
            lot.getStockBatchId() == null,
            lot.getStockBatchId()
        ).orElseThrow(() -> new BusinessException("Stock balance not found for quarantine lot"));
        if (positive(balance.getQuarantineQuantity()).compareTo(quantity) < 0) {
            throw new BusinessException("Not enough quarantined stock to release");
        }
        balance.setQuarantineQuantity(positive(balance.getQuarantineQuantity()).subtract(quantity));
        balance.setAvailableQuantity(positive(balance.getAvailableQuantity()).add(quantity));
        stockBalanceRepository.save(balance);
    }
    private void ensureUnique(QualityQuarantineLot entity) {
        if (entity.getQuarantineNumber() == null || entity.getQuarantineNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndQuarantineNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getQuarantineNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Quality quarantine number already exists");
            });
    }
    private void ensureNoActiveDuplicate(QualityQuarantineLot entity) {
        repository.findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getQualityInspectionId()
            )
            .stream()
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .filter(existing -> !QualityQuarantineStatus.CANCELLED.name().equals(existing.getStatus()))
            .findAny()
            .ifPresent(existing -> {
                throw new BusinessException("Active quarantine lot already exists for quality inspection");
            });
    }
    private String normalizeDisposition(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (QualityDisposition item : QualityDisposition.values()) {
            if (item.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported disposition: " + value);
    }
    private BigDecimal positive(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<QualityQuarantineLotResponse> search(QualityQuarantineLotSearchRequest request, Pageable pageable) {
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
    public QualityQuarantineLotResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public QualityQuarantineLotResponse create(QualityQuarantineLotCreateRequest dto) {
        QualityQuarantineLot entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        QualityQuarantineLot saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public QualityQuarantineLotResponse update(UUID id, QualityQuarantineLotUpdateRequest dto) {
        QualityQuarantineLot entity = findEntity(id);
        requirePurchasingLot(entity);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        QualityQuarantineLot saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        QualityQuarantineLot entity = findEntity(id);
        requirePurchasingLot(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public QualityQuarantineLotResponse restore(UUID id) {
        QualityQuarantineLot entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        requirePurchasingLot(entity);
        entity.restore();
        QualityQuarantineLot saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected QualityQuarantineLot findEntity(UUID id) {
        QualityQuarantineLot entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected boolean companyScoped() {
        return true;
    }
}
