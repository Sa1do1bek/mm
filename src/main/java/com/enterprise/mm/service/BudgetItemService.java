package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.BudgetItemResponse;
import com.enterprise.mm.payload.BudgetItemUpdateRequest;
import com.enterprise.mm.payload.BudgetItemCreateRequest;
import com.enterprise.mm.payload.BudgetCheckResult;
import com.enterprise.mm.payload.BudgetItemSearchRequest;
import com.enterprise.mm.payload.BudgetLedgerReconciliationResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.BudgetItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.BudgetTransaction;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.BudgetTransactionRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BudgetItemService {
    private final BudgetItemRepository repository;
    private final BudgetTransactionRepository transactionRepository;
    private final BudgetItemMapper mapper;
    private final String entityName = "BudgetItem";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<BudgetItemResponse> search(BudgetItemSearchRequest request, Pageable pageable) {
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
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public BudgetItemResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public BudgetItemResponse create(BudgetItemCreateRequest dto) {
        BudgetItem entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        BudgetItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public BudgetItemResponse update(UUID id, BudgetItemUpdateRequest dto) {
        BudgetItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        BudgetItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        BudgetItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public BudgetItemResponse restore(UUID id) {
        BudgetItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        defaultFinancialAmounts(entity);
        ensureUniqueCode(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        BudgetItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected BudgetItem findEntity(UUID id) {
        BudgetItem entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }
    protected void beforeCreate(BudgetItem entity, BudgetItemCreateRequest dto) {
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
        defaultFinancialAmounts(entity);
        ensureUniqueCode(entity);
    }
    protected void beforeUpdate(BudgetItem entity, BudgetItemUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        defaultFinancialAmounts(entity);
        ensureUniqueCode(entity);
    }

    @Transactional(readOnly = true)
    public BudgetCheckResult checkAvailability(UUID budgetItemId, java.math.BigDecimal requestedAmount) {
        if (budgetItemId == null) {
            return new BudgetCheckResult(null, true, false, nvl(requestedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget item linked");
        }
        BudgetItem budgetItem = findEntity(budgetItemId);
        java.math.BigDecimal available = nvl(budgetItem.getAnnualLimit())
            .subtract(nvl(budgetItem.getReservedAmount()))
            .subtract(nvl(budgetItem.getCommittedAmount()))
            .subtract(nvl(budgetItem.getUsedAmount()));
        boolean approved = available.compareTo(nvl(requestedAmount)) >= 0;
        return new BudgetCheckResult(
            budgetItemId,
            approved,
            !approved,
            nvl(requestedAmount),
            available,
            nvl(budgetItem.getReservedAmount()),
            nvl(budgetItem.getUsedAmount()),
            approved ? "Budget available" : "Budget limit exceeded"
        );
    }

    @Transactional
    public BudgetCheckResult reserve(UUID budgetItemId, java.math.BigDecimal requestedAmount, String source) {
        return reserveIdempotent(
            budgetItemId, requestedAmount, source, "LEGACY", budgetItemId, null,
            legacyKey("RESERVE", budgetItemId, source), "LEGACY_RESERVE");
    }

    @Transactional
    public BudgetCheckResult reserveIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal requestedAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(requestedAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(requestedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget reservation required");
        }
        return applyIdempotent(
            budgetItemId,
            requestedAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "RESERVATION",
            budgetItem -> {
                java.math.BigDecimal amount = nvl(requestedAmount);
                java.math.BigDecimal available = nvl(budgetItem.getAnnualLimit())
                    .subtract(nvl(budgetItem.getReservedAmount()))
                    .subtract(nvl(budgetItem.getCommittedAmount()))
                    .subtract(nvl(budgetItem.getUsedAmount()));
                if (available.compareTo(amount) < 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget limit exceeded");
                }
                budgetItem.setReservedAmount(nvl(budgetItem.getReservedAmount()).add(amount));
                return new BudgetDeltas(amount, BigDecimal.ZERO, BigDecimal.ZERO);
            }
        );
    }

    @Transactional
    public BudgetCheckResult ensureAnnualLimitAtLeast(UUID budgetItemId, java.math.BigDecimal approvedAmount, String source) {
        if (budgetItemId == null || nvl(approvedAmount).signum() <= 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(approvedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget allocation required");
        }
        BudgetItem budgetItem = lockEntity(budgetItemId);
        java.math.BigDecimal amount = nvl(approvedAmount);
        if (nvl(budgetItem.getAnnualLimit()).compareTo(amount) < 0) {
            budgetItem.setAnnualLimit(amount);
            repository.save(budgetItem);
            auditService.log("BudgetItem", budgetItemId, "ALLOCATE", source + ": " + amount, null, null);
        }
        return checkAvailability(budgetItemId, java.math.BigDecimal.ZERO);
    }

    @Transactional
    @com.enterprise.mm.security.pilot.PilotCapabilityBoundary(com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION)
    public BudgetCheckResult consume(UUID budgetItemId, java.math.BigDecimal consumedAmount, String source) {
        return consumeIdempotent(
            budgetItemId, consumedAmount, source, "LEGACY", budgetItemId, null,
            legacyKey("CONSUME", budgetItemId, source), "LEGACY_CONSUME");
    }

    @Transactional
    @com.enterprise.mm.security.pilot.PilotCapabilityBoundary(com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION)
    public BudgetCheckResult consumeIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal consumedAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(consumedAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(consumedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget consumption required");
        }
        return applyIdempotent(
            budgetItemId,
            consumedAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "CONSUMPTION",
            budgetItem -> {
                java.math.BigDecimal amount = nvl(consumedAmount);
                java.math.BigDecimal reserved = nvl(budgetItem.getReservedAmount());
                java.math.BigDecimal committed = nvl(budgetItem.getCommittedAmount());
                java.math.BigDecimal used = nvl(budgetItem.getUsedAmount());
                java.math.BigDecimal limit = nvl(budgetItem.getAnnualLimit());
                if (used.add(amount).compareTo(limit) > 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget limit exceeded");
                }
                java.math.BigDecimal covered = reserved.add(committed);
                if (covered.compareTo(amount) < 0) {
                    java.math.BigDecimal uncovered = amount.subtract(covered);
                    java.math.BigDecimal available = limit.subtract(reserved).subtract(committed).subtract(used);
                    if (available.compareTo(uncovered) < 0) {
                        throw new com.enterprise.mm.error.BusinessException("Budget limit exceeded");
                    }
                }
                java.math.BigDecimal remaining = amount;
                java.math.BigDecimal committedApplied = committed.min(remaining);
                budgetItem.setCommittedAmount(committed.subtract(committedApplied));
                remaining = remaining.subtract(committedApplied);
                java.math.BigDecimal reservedApplied = reserved.min(remaining);
                budgetItem.setReservedAmount(reserved.subtract(reservedApplied));
                budgetItem.setUsedAmount(used.add(amount));
                return new BudgetDeltas(reservedApplied.negate(), committedApplied.negate(), amount);
            }
        );
    }

    @Transactional
    @com.enterprise.mm.security.pilot.PilotCapabilityBoundary(com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION)
    public BudgetCheckResult reverseConsumptionIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal reversalAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(reversalAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(reversalAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget consumption reversal required");
        }
        var existingReversal = transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceKey, actionKey);
        if (existingReversal.isPresent()) {
            validateReplay(existingReversal.get(), budgetItemId, reversalAmount, "CONSUMPTION_REVERSAL");
            return checkAvailability(budgetItemId, BigDecimal.ZERO);
        }
        BudgetTransaction original = transactionRepository
            .findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceKey, "CONSUMPTION")
            .orElseThrow(() -> new com.enterprise.mm.error.BusinessException(
                "Original consumption transaction is required before reversal"));
        BigDecimal originalAmount = nvl(original.getAmount()).abs();
        BigDecimal amount = nvl(reversalAmount);
        if (amount.compareTo(originalAmount) > 0) {
            throw new com.enterprise.mm.error.BusinessException("Budget consumption reversal exceeds original consumption");
        }
        BigDecimal alreadyReversed = nvl(transactionRepository.sumConsumptionReversals(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), original.getId()));
        if (alreadyReversed.add(amount).compareTo(originalAmount) > 0) {
            throw new com.enterprise.mm.error.BusinessException("Budget consumption reversal exceeds remaining original consumption");
        }
        return applyIdempotent(
            budgetItemId,
            reversalAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "CONSUMPTION_REVERSAL",
            budgetItem -> {
                java.math.BigDecimal used = nvl(budgetItem.getUsedAmount());
                if (used.compareTo(amount) < 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget consumption reversal exceeds used amount");
                }
                budgetItem.setUsedAmount(used.subtract(amount));
                BigDecimal reservedRestored = nvl(original.getConsumedFromReserved());
                BigDecimal committedRestored = nvl(original.getConsumedFromCommitted());
                BigDecimal direct = nvl(original.getConsumedDirectly());
                BigDecimal ratio = originalAmount.signum() == 0 ? BigDecimal.ZERO : amount.divide(originalAmount, 8, java.math.RoundingMode.HALF_UP);
                reservedRestored = reservedRestored.multiply(ratio);
                committedRestored = committedRestored.multiply(ratio);
                direct = direct.multiply(ratio);
                budgetItem.setReservedAmount(nvl(budgetItem.getReservedAmount()).add(reservedRestored));
                budgetItem.setCommittedAmount(nvl(budgetItem.getCommittedAmount()).add(committedRestored));
                return new BudgetDeltas(reservedRestored, committedRestored, amount.negate());
            }, original.getId());
    }

    @Transactional
    public BudgetCheckResult release(UUID budgetItemId, java.math.BigDecimal releasedAmount, String source) {
        return releaseIdempotent(
            budgetItemId, releasedAmount, source, "LEGACY", budgetItemId, null,
            legacyKey("RELEASE", budgetItemId, source), "LEGACY_RELEASE");
    }

    @Transactional
    public BudgetCheckResult releaseIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal releasedAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(releasedAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(releasedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget release required");
        }
        return applyIdempotent(
            budgetItemId,
            releasedAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "RELEASE",
            budgetItem -> {
                java.math.BigDecimal amount = nvl(releasedAmount);
                java.math.BigDecimal reserved = nvl(budgetItem.getReservedAmount());
                if (reserved.compareTo(amount) < 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget release exceeds reserved amount");
                }
                budgetItem.setReservedAmount(reserved.subtract(amount));
                return new BudgetDeltas(amount.negate(), BigDecimal.ZERO, BigDecimal.ZERO);
            }
        );
    }

    @Transactional
    public BudgetCheckResult commitIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal committedAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(committedAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(committedAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget commitment required");
        }
        return applyIdempotent(
            budgetItemId,
            committedAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "COMMITMENT",
            budgetItem -> {
                java.math.BigDecimal amount = nvl(committedAmount);
                java.math.BigDecimal available = nvl(budgetItem.getAnnualLimit())
                    .subtract(nvl(budgetItem.getReservedAmount()))
                    .subtract(nvl(budgetItem.getCommittedAmount()))
                    .subtract(nvl(budgetItem.getUsedAmount()));
                if (available.compareTo(amount) < 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget limit exceeded");
                }
                budgetItem.setCommittedAmount(nvl(budgetItem.getCommittedAmount()).add(amount));
                return new BudgetDeltas(BigDecimal.ZERO, amount, BigDecimal.ZERO);
            }
        );
    }

    @Transactional
    public BudgetCheckResult reverseCommitmentIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal reversalAmount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey
    ) {
        if (budgetItemId == null || nvl(reversalAmount).signum() == 0) {
            return new BudgetCheckResult(budgetItemId, true, false, nvl(reversalAmount), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "No budget commitment reversal required");
        }
        var existingReversal = transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceKey, actionKey);
        if (existingReversal.isPresent()) {
            validateReplay(existingReversal.get(), budgetItemId, reversalAmount, "COMMITMENT_REVERSAL");
            return checkAvailability(budgetItemId, BigDecimal.ZERO);
        }
        BudgetTransaction original = transactionRepository
            .findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceKey, "COMMITMENT")
            .orElseThrow(() -> new com.enterprise.mm.error.BusinessException(
                "Original commitment transaction is required before reversal"));
        if (nvl(reversalAmount).compareTo(nvl(original.getAmount()).abs()) > 0) {
            throw new com.enterprise.mm.error.BusinessException("Budget commitment reversal exceeds original commitment");
        }
        BigDecimal alreadyReversed = nvl(transactionRepository.sumCommitmentReversals(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), original.getId()));
        if (alreadyReversed.add(nvl(reversalAmount)).compareTo(nvl(original.getAmount()).abs()) > 0) {
            throw new com.enterprise.mm.error.BusinessException("Budget commitment reversal exceeds remaining original commitment");
        }
        return applyIdempotent(
            budgetItemId,
            reversalAmount,
            source,
            sourceType,
            sourceId,
            sourceLineId,
            sourceKey,
            actionKey,
            "COMMITMENT_REVERSAL",
            budgetItem -> {
                java.math.BigDecimal amount = nvl(reversalAmount);
                java.math.BigDecimal committed = nvl(budgetItem.getCommittedAmount());
                if (committed.compareTo(amount) < 0) {
                    throw new com.enterprise.mm.error.BusinessException("Budget commitment reversal exceeds committed amount");
                }
                budgetItem.setCommittedAmount(committed.subtract(amount));
                return new BudgetDeltas(BigDecimal.ZERO, amount.negate(), BigDecimal.ZERO);
            },
            original.getId()
        );
    }

    @Transactional(readOnly = true)
    public BudgetLedgerReconciliationResult reconcileLedger(UUID budgetItemId) {
        BudgetItem budgetItem = findEntity(budgetItemId);
        var totals = transactionRepository.sumActiveDeltas(
            TenantContext.currentTenant(),
            budgetItem.getCompanyId(),
            budgetItemId
        );
        BigDecimal ledgerReserved = totals == null ? BigDecimal.ZERO : nvl(totals.getReservedDelta());
        BigDecimal ledgerCommitted = totals == null ? BigDecimal.ZERO : nvl(totals.getCommittedDelta());
        BigDecimal ledgerUsed = totals == null ? BigDecimal.ZERO : nvl(totals.getUsedDelta());
        BigDecimal storedReserved = nvl(budgetItem.getReservedAmount());
        BigDecimal storedCommitted = nvl(budgetItem.getCommittedAmount());
        BigDecimal storedUsed = nvl(budgetItem.getUsedAmount());
        BigDecimal reservedDifference = storedReserved.subtract(ledgerReserved);
        BigDecimal committedDifference = storedCommitted.subtract(ledgerCommitted);
        BigDecimal usedDifference = storedUsed.subtract(ledgerUsed);
        boolean matched = reservedDifference.compareTo(BigDecimal.ZERO) == 0
            && committedDifference.compareTo(BigDecimal.ZERO) == 0
            && usedDifference.compareTo(BigDecimal.ZERO) == 0;
        return new BudgetLedgerReconciliationResult(
            budgetItemId,
            matched,
            storedReserved,
            ledgerReserved,
            reservedDifference,
            storedCommitted,
            ledgerCommitted,
            committedDifference,
            storedUsed,
            ledgerUsed,
            usedDifference
        );
    }

    private java.math.BigDecimal nvl(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private void defaultFinancialAmounts(BudgetItem entity) {
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getAnnualLimit() == null) {
            entity.setAnnualLimit(BigDecimal.ZERO);
        }
        if (entity.getReservedAmount() == null) {
            entity.setReservedAmount(BigDecimal.ZERO);
        }
        if (entity.getCommittedAmount() == null) {
            entity.setCommittedAmount(BigDecimal.ZERO);
        }
        if (entity.getUsedAmount() == null) {
            entity.setUsedAmount(BigDecimal.ZERO);
        }
    }

    private void ensureUniqueCode(BudgetItem entity) {
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(
            TenantContext.currentTenant(), entity.getCompanyId(), entity.getCode().trim())
            .filter(existing -> !Objects.equals(existing.getId(), entity.getId()))
            .ifPresent(existing -> {
                throw new com.enterprise.mm.error.BadRequestException(
                    "Budget item code already exists in this company");
            });
    }

    private void validateScope(BudgetItem entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private BudgetItem lockEntity(UUID id) {
        BudgetItem entity = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private BudgetCheckResult applyIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal amount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey,
        String transactionType,
        java.util.function.Function<BudgetItem, BudgetDeltas> mutation
    ) {
        return applyIdempotent(
            budgetItemId, amount, source, sourceType, sourceId, sourceLineId, sourceKey, actionKey,
            transactionType, mutation, null);
    }

    private BudgetCheckResult applyIdempotent(
        UUID budgetItemId,
        java.math.BigDecimal amount,
        String source,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey,
        String transactionType,
        java.util.function.Function<BudgetItem, BudgetDeltas> mutation,
        UUID originalTransactionId
    ) {
        String normalizedSourceKey = requireKey(sourceKey, "sourceKey");
        String normalizedActionKey = requireKey(actionKey, "actionKey");
        BudgetItem budgetItem = lockEntity(budgetItemId);
        var existing = transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(),
            budgetItem.getCompanyId(),
            normalizedSourceKey,
            normalizedActionKey
        );
        if (existing.isPresent()) {
            validateReplay(existing.get(), budgetItemId, amount, transactionType);
            return checkAvailability(budgetItemId, java.math.BigDecimal.ZERO);
        }

        BudgetDeltas deltas = mutation.apply(budgetItem);
        repository.save(budgetItem);
        recordTransaction(
            budgetItem,
            transactionType,
            transactionAmount(transactionType, nvl(amount)),
            deltas,
            sourceType,
            sourceId,
            sourceLineId,
            normalizedSourceKey,
            normalizedActionKey,
            source,
            originalTransactionId
        );
        auditService.log("BudgetItem", budgetItemId, transactionType, source + ": " + nvl(amount), null, null);
        return checkAvailability(budgetItemId, java.math.BigDecimal.ZERO);
    }

    private void recordTransaction(
        BudgetItem budgetItem,
        String transactionType,
        java.math.BigDecimal amount,
        BudgetDeltas deltas,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceKey,
        String actionKey,
        String description,
        UUID originalTransactionId
    ) {
        BudgetTransaction transaction = new BudgetTransaction();
        transaction.setCode("BUD-TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        transaction.setName(transactionType + " budget transaction");
        transaction.setStatus("POSTED");
        transaction.setCompanyId(budgetItem.getCompanyId());
        transaction.setBudgetItemId(budgetItem.getId());
        transaction.setTransactionType(transactionType);
        transaction.setAmount(amount);
        transaction.setReservedDelta(nvl(deltas.reservedDelta()));
        transaction.setCommittedDelta(nvl(deltas.committedDelta()));
        transaction.setUsedDelta(nvl(deltas.usedDelta()));
        transaction.setSourceType(sourceType == null || sourceType.isBlank() ? "UNKNOWN" : sourceType);
        transaction.setSourceId(sourceId);
        transaction.setSourceLineId(sourceLineId);
        transaction.setSourceKey(sourceKey);
        transaction.setActionKey(actionKey);
        transaction.setIdempotencyKey(sourceKey + ":" + actionKey);
        transaction.setOriginalTransactionId(originalTransactionId);
        transaction.setReversalOfTransactionId(originalTransactionId);
        transaction.setCurrencyCode(budgetItem.getCurrencyCode() == null ? "UZS" : budgetItem.getCurrencyCode().toUpperCase(Locale.ROOT));
        if ("CONSUMPTION".equals(transactionType)) {
            transaction.setConsumedFromReserved(nvl(deltas.reservedDelta()).negate());
            transaction.setConsumedFromCommitted(nvl(deltas.committedDelta()).negate());
            transaction.setConsumedDirectly(nvl(amount).subtract(transaction.getConsumedFromReserved()).subtract(transaction.getConsumedFromCommitted()));
        }
        transaction.setDescription(description);
        transactionRepository.save(transaction);
    }

    private void validateReplay(BudgetTransaction existing, UUID budgetItemId, java.math.BigDecimal amount, String transactionType) {
        if (!Objects.equals(existing.getBudgetItemId(), budgetItemId)
            || !Objects.equals(existing.getTransactionType(), transactionType)
            || existing.getAmount().abs().compareTo(nvl(amount).abs()) != 0) {
            throw new com.enterprise.mm.error.ConflictException("Budget transaction idempotency key was already used for a different operation");
        }
    }

    private String requireKey(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new com.enterprise.mm.error.BusinessException(label + " is required for source-keyed budget operation");
        }
        return value.trim();
    }

    private java.math.BigDecimal transactionAmount(String transactionType, java.math.BigDecimal amount) {
        return switch (transactionType) {
            case "RELEASE", "COMMITMENT_REVERSAL", "CONSUMPTION_REVERSAL" -> amount.negate();
            default -> amount;
        };
    }

    private String legacyKey(String action, UUID budgetItemId, String source) {
        String normalizedSource = source == null ? "UNSPECIFIED" : source.trim().replaceAll("\\s+", " ");
        String key = "LEGACY:" + action + ":" + budgetItemId + ":" + normalizedSource;
        return key.length() <= 220 ? key : key.substring(0, 220);
    }

    private record BudgetDeltas(BigDecimal reservedDelta, BigDecimal committedDelta, BigDecimal usedDelta) {
    }

    protected boolean companyScoped() {
        return true;
    }
}
