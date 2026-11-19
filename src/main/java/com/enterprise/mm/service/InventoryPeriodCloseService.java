package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryFinanceDtos.InventoryPeriodClosePrepareRequest;
import com.enterprise.mm.payload.InventoryFinanceDtos.InventoryPeriodClosePrepareResult;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseUpdateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCreateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseSearchRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseValidationResult;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.InventoryPeriodCloseMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryPeriodClose;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import com.enterprise.mm.repository.InventoryPeriodCloseRepository;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryPeriodCloseService {
    private final String entityName = "InventoryPeriodClose";
    private final InventoryPeriodCloseRepository repository;
    private final InventoryPeriodCloseMapper mapper;
    private final InventoryFinanceCalculationService calculationService;
    private final InventoryAccountingReconciliationRepository reconciliationRepository;
    private final InventoryAccountingReconciliationService reconciliationService;
    private final InventoryValuationReconciliationService valuationReconciliationService;
    private final FinancialAuthorizationService financialAuthorizationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final InventoryPeriodCloseActionPolicy inventoryPeriodCloseActionPolicy;

    protected void beforeCreate(InventoryPeriodClose entity, InventoryPeriodCloseCreateRequest dto) {
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
    protected void beforeUpdate(InventoryPeriodClose entity, InventoryPeriodCloseUpdateRequest dto) {
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("CLOSED inventory period cannot be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public InventoryPeriodClosePrepareResult prepare(UUID id, InventoryPeriodClosePrepareRequest request) {
        if (Boolean.TRUE.equals(request == null ? null : request.closeIfMatched())) {
            throw new BusinessException("Prepare cannot close the period directly; submit a separate close approval request");
        }
        InventoryPeriodClose entity = findEntity(id);
        inventoryPeriodCloseActionPolicy.requireActionAllowed(entity, "PREPARE_RECONCILIATION");
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("Inventory period already closed");
        }
        applyPrepareRequest(entity, request);
        fillCalculatedValues(entity);
        validateNoClosedOverlap(entity);
        validateNoOpenMovements(entity);
        validateCostingReadiness(entity);
        InventoryAccountingReconciliationResponse reconciliation = reconciliationService.prepareForPeriod(
            entity.getPeriodStart(),
            entity.getPeriodEnd(),
            entity.getWarehouseId(),
            valueOrDefault(request == null ? null : request.accountingSystem(), "FI"),
            request == null ? null : request.externalReference(),
            request == null ? null : request.accountingValue(),
            request == null ? null : request.toleranceAmount()
        );
        if (Boolean.TRUE.equals(request == null ? null : request.postMatchedReconciliation()) && "MATCHED".equals(reconciliation.getDifferenceStatus()) && !"POSTED".equals(reconciliation.getStatus())) {
            reconciliation = reconciliationService.post(reconciliation.getId());
        }
        entity.setAccountingReconciliationId(reconciliation.getId());
        syncReconciliationSummary(entity, reconciliation);
        entity.setStatus("CALCULATED");
        InventoryPeriodClose saved = repository.save(entity);
        auditService.log("InventoryPeriodClose", saved.getId(), "PREPARE_CLOSE", saved.getCode(), null, null);
        boolean ready = "POSTED".equals(reconciliation.getStatus()) && "MATCHED".equals(reconciliation.getDifferenceStatus());
        return new InventoryPeriodClosePrepareResult(mapper.toResponse(saved), reconciliation, ready, ready ? "Ready to close" : "FI reconciliation must be POSTED and MATCHED");
    }
    @Transactional
    public InventoryPeriodClosePrepareResult prepare(InventoryPeriodClosePrepareRequest request) {
        if (Boolean.TRUE.equals(request == null ? null : request.closeIfMatched())) {
            throw new BusinessException("Prepare cannot close the period directly; submit a separate close approval request");
        }
        LocalDate periodStart = request == null || request.periodStart() == null ? LocalDate.now().withDayOfMonth(1) : request.periodStart();
        LocalDate periodEnd = request == null || request.periodEnd() == null ? LocalDate.now() : request.periodEnd();
        UUID warehouseId = request == null ? null : request.warehouseId();
        if (periodEnd.isBefore(periodStart)) {
            throw new BusinessException("Period end cannot be before period start");
        }
        InventoryPeriodClose entity = repository.findOpenByPeriod(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                warehouseId == null,
                warehouseId,
                periodStart,
                periodEnd
            ).stream()
            .findFirst()
            .orElseGet(InventoryPeriodClose::new);
        if (entity.getId() == null) {
            entity.setPeriodStart(periodStart);
            entity.setPeriodEnd(periodEnd);
            entity.setWarehouseId(warehouseId);
            entity.setName("Inventory close " + periodStart + " - " + periodEnd);
            beforeCreate(entity, new InventoryPeriodCloseCreateRequest());
            entity = repository.save(entity);
        }
        return prepare(entity.getId(), request);
    }
    @Transactional
    public InventoryPeriodCloseResponse calculate(UUID id) {
        return calculate(id, null);
    }

    @Transactional
    public InventoryPeriodCloseResponse calculate(UUID id, Long expectedVersion) {
        InventoryPeriodClose entity = findEntity(id);
        requireVersion(entity, expectedVersion);
        inventoryPeriodCloseActionPolicy.requireActionAllowed(entity, "CALCULATE");
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("Inventory period already closed");
        }
        fillCalculatedValues(entity);
        InventoryPeriodClose saved = repository.save(entity);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryPeriodCloseResponse close(UUID id) {
        return close(id, null);
    }

    @Transactional
    public InventoryPeriodCloseResponse close(UUID id, Long expectedVersion) {
        com.enterprise.mm.service.approval.ApprovedOperationContext.require(
            com.enterprise.mm.service.approval.InventoryPeriodCloseOperationHandler.MODULE_KEY, id
        );
        financialAuthorizationService.requireFinance("close inventory period");
        InventoryPeriodClose entity = findEntity(id);
        // UX-LIVE-04: enforce CLOSE action policy
        inventoryPeriodCloseActionPolicy.requireActionAllowed(entity, "CLOSE");
        requireVersion(entity, expectedVersion);
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("Inventory period already closed");
        }
        fillCalculatedValues(entity);
        validateNoClosedOverlap(entity);
        validateNoOpenMovements(entity);
        validateCostingReadiness(entity);
        validateFiReconciliation(entity);
        entity.setStatus("CLOSED");
        entity.setClosedAt(LocalDateTime.now());
        InventoryPeriodClose saved = repository.save(entity);
        integrationEventService.emit("INVENTORY.PERIOD_CLOSED", "InventoryPeriodClose", saved.getId(), Map.ofEntries(
            Map.entry("periodCloseId", saved.getId()),
            Map.entry("periodCloseCode", saved.getCode()),
            Map.entry("periodStart", saved.getPeriodStart()),
            Map.entry("periodEnd", saved.getPeriodEnd()),
            Map.entry("warehouseId", saved.getWarehouseId() == null ? "" : saved.getWarehouseId()),
            Map.entry("closedAt", saved.getClosedAt().toString()),
            Map.entry("totalStockValue", saved.getTotalStockValue() == null ? BigDecimal.ZERO : saved.getTotalStockValue()),
            Map.entry("transitStockValue", saved.getTransitStockValue()),
            Map.entry("landedCostAmount", saved.getLandedCostAmount() == null ? BigDecimal.ZERO : saved.getLandedCostAmount()),
            Map.entry("fxDifferenceAmount", saved.getFxDifferenceAmount() == null ? BigDecimal.ZERO : saved.getFxDifferenceAmount()),
            Map.entry("movementCount", saved.getMovementCount()),
            Map.entry("valuationCount", saved.getValuationCount()),
            Map.entry("status", saved.getStatus())
        ));
        auditService.log("InventoryPeriodClose", saved.getId(), "CLOSE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public void validateCloseForApproval(UUID id, Long expectedVersion) {
        financialAuthorizationService.requireFinance("close inventory period");
        InventoryPeriodClose entity = findEntity(id);
        inventoryPeriodCloseActionPolicy.requireActionAllowed(entity, "CLOSE");
        requireVersion(entity, expectedVersion);
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("Inventory period already closed");
        }
    }

    @Transactional(readOnly = true)
    public List<InventoryValuationReconciliationIssueResponse> issues(UUID id) {
        InventoryPeriodClose entity = findEntity(id);
        return valuationReconciliationService.blockerIssues(entity.getWarehouseId());
    }

    @Transactional(readOnly = true)
    public InventoryPeriodCloseValidationResult validate(UUID id) {
        InventoryPeriodClose entity = findEntity(id);
        List<InventoryValuationReconciliationIssueResponse> issues = valuationReconciliationService.run(entity.getWarehouseId());
        long blockers = issues.stream().filter(issue -> "BLOCKER".equals(issue.severity())).count();
        long warnings = issues.stream().filter(issue -> "WARNING".equals(issue.severity())).count();
        return new InventoryPeriodCloseValidationResult(
            entity.getId(),
            blockers > 0 ? "FAILED" : "PASSED",
            blockers,
            warnings,
            issues
        );
    }
    @Transactional
    public InventoryPeriodCloseResponse reopen(UUID id) {
        return reopen(id, null, "Compatibility reopen");
    }

    @Transactional
    public InventoryPeriodCloseResponse reopen(UUID id, Long expectedVersion, String reason) {
        if (!financialAuthorizationService.hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")) {
            throw new BusinessException("MM manager or finance lead role is required to reopen inventory period");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new BusinessException("Reopen reason must contain at least 5 characters");
        }
        InventoryPeriodClose entity = findEntity(id);
        // UX-LIVE-04: enforce REOPEN action policy
        inventoryPeriodCloseActionPolicy.requireActionAllowed(entity, "REOPEN");
        requireVersion(entity, expectedVersion);
        if (!"CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("Only CLOSED inventory period can be reopened");
        }
        entity.setStatus("REOPENED");
        entity.setClosedAt(null);
        InventoryPeriodClose saved = repository.save(entity);
        integrationEventService.emit("INVENTORY.PERIOD_REOPENED", "InventoryPeriodClose", saved.getId(), Map.of(
            "periodStart", saved.getPeriodStart(),
            "periodEnd", saved.getPeriodEnd(),
            "warehouseId", saved.getWarehouseId() == null ? "" : saved.getWarehouseId(),
            "reason", reason.trim()
        ));
        auditService.log("InventoryPeriodClose", saved.getId(), "REOPEN", saved.getCode() + "; reason=" + reason.trim(), null, null);
        return mapper.toResponse(saved);
    }

    private void requireVersion(InventoryPeriodClose entity, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        long actualVersion = entity.getVersion() == null ? 0L : entity.getVersion();
        if (actualVersion != expectedVersion) {
            throw new BusinessException("Inventory period close version conflict: expected " + expectedVersion + " but was " + actualVersion);
        }
    }
    private void normalize(InventoryPeriodClose entity) {
        if (entity.getPeriodStart() == null) {
            entity.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        }
        if (entity.getPeriodEnd() == null) {
            entity.setPeriodEnd(LocalDate.now());
        }
        if (entity.getPeriodEnd().isBefore(entity.getPeriodStart())) {
            throw new BusinessException("Period end cannot be before period start");
        }
        entity.setTotalStockValue(nvl(entity.getTotalStockValue()));
        entity.setLandedCostAmount(nvl(entity.getLandedCostAmount()));
        entity.setFxDifferenceAmount(nvl(entity.getFxDifferenceAmount()));
        entity.setAccountingDifferenceAmount(nvl(entity.getAccountingDifferenceAmount()));
    }
    private void fillCalculatedValues(InventoryPeriodClose entity) {
        normalize(entity);
        entity.setTotalStockValue(calculationService.inventoryValue(entity.getWarehouseId()));
        entity.setTransitStockValue(nvl(calculationService.transitInventoryValue(entity.getWarehouseId())));
        entity.setLandedCostAmount(calculationService.postedLandedCostAmount(entity.getPeriodStart(), entity.getPeriodEnd()));
        entity.setFxDifferenceAmount(calculationService.postedFxDifferenceAmount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd()));
        entity.setMovementCount(calculationService.postedMovementCount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd()));
        entity.setOpenMovementCount(calculationService.openMovementCount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd()));
        entity.setValuationCount(calculationService.valuationCount(entity.getWarehouseId()));
    }
    private void validateFiReconciliation(InventoryPeriodClose entity) {
        if (entity.getAccountingReconciliationId() == null) {
            throw new BusinessException("Posted FI accounting reconciliation is required before period close");
        }
        var reconciliation = reconciliationRepository.findByIdAndIsDeletedFalse(entity.getAccountingReconciliationId())
            .filter(item -> !item.isDeleted())
            .orElseThrow(() -> new BusinessException("Accounting reconciliation not found: " + entity.getAccountingReconciliationId()));
        if (!"POSTED".equals(reconciliation.getStatus())) {
            throw new BusinessException("Accounting reconciliation must be POSTED before period close");
        }
        if (!"MATCHED".equals(reconciliation.getDifferenceStatus())) {
            throw new BusinessException("Accounting reconciliation must be MATCHED before period close");
        }
        if (!entity.getPeriodStart().equals(reconciliation.getPeriodStart()) || !entity.getPeriodEnd().equals(reconciliation.getPeriodEnd())) {
            throw new BusinessException("Accounting reconciliation period does not match period close");
        }
        if (entity.getWarehouseId() == null ? reconciliation.getWarehouseId() != null : !entity.getWarehouseId().equals(reconciliation.getWarehouseId())) {
            throw new BusinessException("Accounting reconciliation warehouse does not match period close");
        }
        syncReconciliationSummary(entity, mapperReconciliation(reconciliation));
    }
    private void validateNoClosedOverlap(InventoryPeriodClose entity) {
        if (repository.existsClosedOverlap(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            entity.getWarehouseId() == null,
            entity.getWarehouseId(),
            entity.getPeriodStart(),
            entity.getPeriodEnd(),
            entity.getId() == null,
            entity.getId()
        )) {
            throw new BusinessException("Inventory period is already closed for this warehouse and date range");
        }
    }
    private void validateNoOpenMovements(InventoryPeriodClose entity) {
        if (entity.getOpenMovementCount() > 0) {
            throw new BusinessException("Cannot close inventory period with open stock movements: " + entity.getOpenMovementCount());
        }
    }
    private void validateCostingReadiness(InventoryPeriodClose entity) {
        long missingCostLayers = calculationService.missingCostLayerBalanceCount(entity.getWarehouseId());
        if (missingCostLayers > 0) {
            throw new BusinessException("Cannot close inventory period with stock balances missing active cost layers: " + missingCostLayers);
        }
        long zeroCostInbound = calculationService.zeroCostInboundCount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd());
        if (zeroCostInbound > 0) {
            throw new BusinessException("Cannot close inventory period with posted zero-cost inbound movements: " + zeroCostInbound);
        }
        long openValuationDocuments = calculationService.openValuationDocumentCount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd());
        if (openValuationDocuments > 0) {
            throw new BusinessException("Cannot close inventory period with unposted valuation documents: " + openValuationDocuments);
        }
        long openLandedCostAllocations = calculationService.openLandedCostAllocationLineCount(entity.getWarehouseId(), entity.getPeriodStart(), entity.getPeriodEnd());
        if (openLandedCostAllocations > 0) {
            throw new BusinessException("Cannot close inventory period with unposted landed cost allocation lines: " + openLandedCostAllocations);
        }
        long reconciliationBlockers = valuationReconciliationService.blockerIssues(entity.getWarehouseId()).size();
        if (reconciliationBlockers > 0) {
            throw new BusinessException("Cannot close inventory period with valuation reconciliation blockers: " + reconciliationBlockers);
        }
    }
    private void applyPrepareRequest(InventoryPeriodClose entity, InventoryPeriodClosePrepareRequest request) {
        if (request == null) {
            return;
        }
        if (request.periodStart() != null) {
            entity.setPeriodStart(request.periodStart());
        }
        if (request.periodEnd() != null) {
            entity.setPeriodEnd(request.periodEnd());
        }
        if (request.warehouseId() != null) {
            entity.setWarehouseId(request.warehouseId());
        }
        normalize(entity);
    }
    private void syncReconciliationSummary(InventoryPeriodClose entity, InventoryAccountingReconciliationResponse reconciliation) {
        entity.setAccountingReconciliationStatus(reconciliation.getDifferenceStatus());
        entity.setAccountingDifferenceAmount(nvl(reconciliation.getDifferenceAmount()));
    }
    private InventoryAccountingReconciliationResponse mapperReconciliation(InventoryAccountingReconciliation entity) {
        InventoryAccountingReconciliationResponse dto = new InventoryAccountingReconciliationResponse();
        dto.setId(entity.getId());
        dto.setStatus(entity.getStatus());
        dto.setDifferenceStatus(entity.getDifferenceStatus());
        dto.setDifferenceAmount(entity.getDifferenceAmount());
        return dto;
    }
    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<InventoryPeriodCloseResponse> search(InventoryPeriodCloseSearchRequest request, Pageable pageable) {
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
    public InventoryPeriodCloseResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public InventoryPeriodCloseResponse create(InventoryPeriodCloseCreateRequest dto) {
        InventoryPeriodClose entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryPeriodClose saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryPeriodCloseResponse update(UUID id, InventoryPeriodCloseUpdateRequest dto) {
        InventoryPeriodClose entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        InventoryPeriodClose saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryPeriodClose entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireDeletable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryPeriodCloseResponse restore(UUID id) {
        InventoryPeriodClose entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireRestorable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryPeriodClose saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireEditable(InventoryPeriodClose entity) {
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("CLOSED inventory period cannot be edited");
        }
    }
    private void requireDeletable(InventoryPeriodClose entity) {
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("CLOSED inventory period cannot be deleted; use controlled reopen");
        }
    }
    private void requireRestorable(InventoryPeriodClose entity) {
        if ("CLOSED".equals(entity.getStatus())) {
            throw new BusinessException("CLOSED inventory period cannot be restored; use controlled reopen");
        }
    }
    protected InventoryPeriodClose findEntity(UUID id) {
        InventoryPeriodClose entity = repository.findByIdAndIsDeletedFalse(id)
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
