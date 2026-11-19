package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationUpdateRequest;
import com.enterprise.mm.payload.InventoryAccountingReconciliationCreateRequest;
import com.enterprise.mm.payload.InventoryAccountingReconciliationSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.InventoryAccountingReconciliationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryAccountingReconciliationService {
    private final String entityName = "InventoryAccountingReconciliation";
    private final InventoryAccountingReconciliationRepository repository;
    private final InventoryAccountingReconciliationMapper mapper;
    private final InventoryFinanceCalculationService calculationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final InventoryPeriodCloseActionPolicy inventoryPeriodCloseActionPolicy;

    protected void beforeCreate(InventoryAccountingReconciliation entity, InventoryAccountingReconciliationCreateRequest dto) {
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

        entity.setStatus("DRAFT");
        normalize(entity);
    }
    protected void beforeUpdate(InventoryAccountingReconciliation entity, InventoryAccountingReconciliationUpdateRequest dto) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED accounting reconciliation cannot be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public InventoryAccountingReconciliationResponse prepareForPeriod(
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID warehouseId,
        String accountingSystem,
        String externalReference,
        BigDecimal accountingValue,
        BigDecimal toleranceAmount
    ) {
        InventoryAccountingReconciliation entity = repository.findByPeriodAndSystem(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                warehouseId == null,
                warehouseId,
                periodStart,
                periodEnd,
                normalizeAccountingSystem(accountingSystem)
            ).stream()
            .findFirst()
            .orElseGet(InventoryAccountingReconciliation::new);
        if ("POSTED".equals(entity.getStatus())) {
            return mapper.toResponse(entity);
        }
        if (entity.getId() == null) {
            entity.setPeriodStart(periodStart);
            entity.setPeriodEnd(periodEnd);
            entity.setWarehouseId(warehouseId);
            entity.setAccountingSystem(accountingSystem);
            entity.setAccountingValue(accountingValue);
            entity.setToleranceAmount(toleranceAmount);
            entity.setExternalReference(externalReference);
            beforeCreate(entity, new InventoryAccountingReconciliationCreateRequest());
        } else {
            entity.setAccountingValue(nvl(accountingValue));
            entity.setToleranceAmount(nvl(toleranceAmount));
            entity.setExternalReference(trimToNull(externalReference));
            normalize(entity);
        }
        fillReconciliation(entity);
        entity.setStatus("RECONCILED");
        entity.setReconciledAt(LocalDateTime.now());
        InventoryAccountingReconciliation saved = repository.save(entity);
        auditService.log("InventoryAccountingReconciliation", saved.getId(), "PREPARE_PERIOD_RECONCILIATION", saved.getDifferenceStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryAccountingReconciliationResponse reconcile(UUID id) {
        InventoryAccountingReconciliation entity = findEntity(id);
        inventoryPeriodCloseActionPolicy.requireFinanceRoleFor("RECONCILE");
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED accounting reconciliation cannot be recalculated");
        }
        fillReconciliation(entity);
        entity.setStatus("RECONCILED");
        entity.setReconciledAt(LocalDateTime.now());
        InventoryAccountingReconciliation saved = repository.save(entity);
        auditService.log("InventoryAccountingReconciliation", saved.getId(), "RECONCILE", saved.getDifferenceStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryAccountingReconciliationResponse post(UUID id) {
        InventoryAccountingReconciliation entity = findEntity(id);
        inventoryPeriodCloseActionPolicy.requireFinanceRoleFor("POST_RECONCILIATION");
        if (!"RECONCILED".equals(entity.getStatus())) {
            throw new BusinessException("Only RECONCILED accounting reconciliation can be posted");
        }
        fillReconciliation(entity);
        entity.setStatus("POSTED");
        entity.setPostedAt(LocalDateTime.now());
        InventoryAccountingReconciliation saved = repository.save(entity);
        integrationEventService.emit("INVENTORY.ACCOUNTING_RECONCILIATION_POSTED", "InventoryAccountingReconciliation", saved.getId(), Map.of(
            "periodStart", saved.getPeriodStart(),
            "periodEnd", saved.getPeriodEnd(),
            "warehouseId", saved.getWarehouseId() == null ? "" : saved.getWarehouseId(),
            "accountingSystem", saved.getAccountingSystem(),
            "externalReference", saved.getExternalReference() == null ? "" : saved.getExternalReference(),
            "inventoryValue", saved.getInventoryValue(),
            "accountingValue", saved.getAccountingValue(),
            "differenceAmount", saved.getDifferenceAmount(),
            "differenceStatus", saved.getDifferenceStatus()
        ));
        auditService.log("InventoryAccountingReconciliation", saved.getId(), "POST", saved.getDifferenceStatus(), null, null);
        return mapper.toResponse(saved);
    }
    private void normalize(InventoryAccountingReconciliation entity) {
        if (entity.getPeriodStart() == null) {
            entity.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        }
        if (entity.getPeriodEnd() == null) {
            entity.setPeriodEnd(LocalDate.now());
        }
        if (entity.getPeriodEnd().isBefore(entity.getPeriodStart())) {
            throw new BusinessException("Period end cannot be before period start");
        }
        entity.setAccountingSystem(normalizeAccountingSystem(entity.getAccountingSystem()));
        entity.setExternalReference(trimToNull(entity.getExternalReference()));
        entity.setInventoryValue(nvl(entity.getInventoryValue()));
        entity.setAccountingValue(nvl(entity.getAccountingValue()));
        entity.setDifferenceAmount(nvl(entity.getDifferenceAmount()));
        entity.setToleranceAmount(nvl(entity.getToleranceAmount()));
        entity.setDifferenceStatus(entity.getDifferenceStatus() == null || entity.getDifferenceStatus().isBlank() ? "NOT_CHECKED" : entity.getDifferenceStatus());
    }
    private void fillReconciliation(InventoryAccountingReconciliation entity) {
        normalize(entity);
        entity.setInventoryValue(calculationService.inventoryValue(entity.getWarehouseId()));
        entity.setTransitStockValue(nvl(calculationService.transitInventoryValue(entity.getWarehouseId())));
        entity.setDifferenceAmount(entity.getInventoryValue().subtract(entity.getAccountingValue()));
        entity.setDifferenceStatus(entity.getDifferenceAmount().abs().compareTo(entity.getToleranceAmount()) <= 0 ? "MATCHED" : "DIFFERENCE");
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private String normalizeAccountingSystem(String accountingSystem) {
        return (accountingSystem == null || accountingSystem.isBlank() ? "FI" : accountingSystem).trim().toUpperCase(Locale.ROOT);
    }
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
    @Transactional(readOnly = true)
    public Page<InventoryAccountingReconciliationResponse> search(InventoryAccountingReconciliationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
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
            filter.warehouseId(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public InventoryAccountingReconciliationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public InventoryAccountingReconciliationResponse create(InventoryAccountingReconciliationCreateRequest dto) {
        InventoryAccountingReconciliation entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryAccountingReconciliation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryAccountingReconciliationResponse update(UUID id, InventoryAccountingReconciliationUpdateRequest dto) {
        InventoryAccountingReconciliation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        InventoryAccountingReconciliation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryAccountingReconciliation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryAccountingReconciliationResponse restore(UUID id) {
        InventoryAccountingReconciliation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryAccountingReconciliation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireMutable(InventoryAccountingReconciliation entity) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED accounting reconciliation is immutable; create a correction");
        }
    }
    protected InventoryAccountingReconciliation findEntity(UUID id) {
        InventoryAccountingReconciliation entity = repository.findByIdAndIsDeletedFalse(id)
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
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}
