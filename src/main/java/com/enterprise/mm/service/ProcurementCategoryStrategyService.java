package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ProcurementCategoryStrategyResponse;
import com.enterprise.mm.payload.ProcurementCategoryStrategyUpdateRequest;
import com.enterprise.mm.payload.ProcurementCategoryStrategyCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProcurementCategoryStrategyMapper;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ProcurementRiskLevel;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementStrategyStatus;
import com.enterprise.mm.enums.ProcurementStrategyType;
import com.enterprise.mm.entity.ProcurementCategoryStrategy;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.ProcurementCategoryStrategyRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ProcurementCategoryStrategySearchRequest;

@Service
@RequiredArgsConstructor
public class ProcurementCategoryStrategyService {
    private final String entityName = "ProcurementCategoryStrategy";
    private final ProcurementCategoryStrategyRepository repository;
    private final ProcurementCategoryStrategyMapper mapper;
    private final MaterialGroupRepository materialGroupRepository;
    private final BudgetItemRepository budgetItemRepository;
    private final EmployeeRepository employeeRepository;
    private final IntegrationEventService integrationEventService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;
    private final ProcurementMethodPolicy procurementMethodPolicy;

    protected void beforeCreate(ProcurementCategoryStrategy entity, ProcurementCategoryStrategyCreateRequest dto) {
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

        entity.setStatus(ProcurementStrategyStatus.DRAFT.name());
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
    }
    @Transactional
    public ProcurementCategoryStrategyResponse update(UUID id, ProcurementCategoryStrategyUpdateRequest dto) {
        ProcurementCategoryStrategy current = findEntity(id);
        if (ProcurementStrategyStatus.CLOSED.name().equals(current.getStatus()) || ProcurementStrategyStatus.CANCELLED.name().equals(current.getStatus())) {
            throw new BusinessException("Closed or cancelled procurement category strategy cannot be edited");
        }
        dto.setStatus(current.getStatus());
        ProcurementCategoryStrategy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ProcurementCategoryStrategy saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected void beforeUpdate(ProcurementCategoryStrategy entity, ProcurementCategoryStrategyUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        validate(entity);
        ensureUnique(entity);
    }
    @Transactional
    public ProcurementCategoryStrategyResponse submit(UUID id, String comment) {
        ProcurementCategoryStrategy strategy = findEntity(id);
        requireStatus(strategy, ProcurementStrategyStatus.DRAFT);
        strategy.setStatus(ProcurementStrategyStatus.UNDER_REVIEW.name());
        auditService.log("ProcurementCategoryStrategy", id, "SUBMIT", comment, null, null);
        return mapper.toResponse(repository.save(strategy));
    }
    @Transactional
    public ProcurementCategoryStrategyResponse approve(UUID id, String comment) {
        ProcurementCategoryStrategy strategy = findEntity(id);
        requireStatus(strategy, ProcurementStrategyStatus.UNDER_REVIEW);
        segregationOfDutyRuleService.validate("ProcurementCategoryStrategy", "APPROVE", strategy);
        strategy.setStatus(ProcurementStrategyStatus.APPROVED.name());
        strategy.setApprovedAt(LocalDateTime.now());
        if (nvl(strategy.getApprovedSpendLimit()).signum() == 0) {
            strategy.setApprovedSpendLimit(nvl(strategy.getPlannedSpendAmount()));
        }
        integrationEventService.emit("PROCUREMENT.CATEGORY_STRATEGY_APPROVED", "ProcurementCategoryStrategy", id, Map.of(
            "strategyId", id,
            "materialGroupId", strategy.getMaterialGroupId(),
            "strategyYear", strategy.getStrategyYear(),
            "plannedSpendAmount", nvl(strategy.getPlannedSpendAmount()),
            "approvedSpendLimit", nvl(strategy.getApprovedSpendLimit()),
            "currency", strategy.getCurrencyCode()
        ));
        auditService.log("ProcurementCategoryStrategy", id, "APPROVE", comment, null, null);
        return mapper.toResponse(repository.save(strategy));
    }
    @Transactional
    public ProcurementCategoryStrategyResponse activate(UUID id) {
        ProcurementCategoryStrategy strategy = findEntity(id);
        requireStatus(strategy, ProcurementStrategyStatus.APPROVED);
        strategy.setStatus(ProcurementStrategyStatus.ACTIVE.name());
        strategy.setActivatedAt(LocalDateTime.now());
        auditService.log("ProcurementCategoryStrategy", id, "ACTIVATE", strategy.getCode(), null, null);
        return mapper.toResponse(repository.save(strategy));
    }
    @Transactional
    public ProcurementCategoryStrategyResponse close(UUID id) {
        ProcurementCategoryStrategy strategy = findEntity(id);
        if (!ProcurementStrategyStatus.APPROVED.name().equals(strategy.getStatus()) && !ProcurementStrategyStatus.ACTIVE.name().equals(strategy.getStatus())) {
            throw new BusinessException("Only APPROVED or ACTIVE procurement category strategy can be closed");
        }
        strategy.setStatus(ProcurementStrategyStatus.CLOSED.name());
        strategy.setClosedAt(LocalDateTime.now());
        auditService.log("ProcurementCategoryStrategy", id, "CLOSE", strategy.getCode(), null, null);
        return mapper.toResponse(repository.save(strategy));
    }
    @Transactional
    public ProcurementCategoryStrategyResponse cancel(UUID id) {
        ProcurementCategoryStrategy strategy = findEntity(id);
        if (ProcurementStrategyStatus.CLOSED.name().equals(strategy.getStatus())) {
            throw new BusinessException("Closed procurement category strategy cannot be cancelled");
        }
        strategy.setStatus(ProcurementStrategyStatus.CANCELLED.name());
        auditService.log("ProcurementCategoryStrategy", id, "CANCEL", strategy.getCode(), null, null);
        return mapper.toResponse(repository.save(strategy));
    }
    private void normalize(ProcurementCategoryStrategy entity) {
        if (entity.getStrategyYear() == null) {
            entity.setStrategyYear(Year.now().getValue());
        }
        entity.setStrategyType(normalizeEnum(entity.getStrategyType(), ProcurementStrategyType.SUPPLY_SECURITY.name(), enumNames(ProcurementStrategyType.values()), "strategyType"));
        entity.setSourcingApproach(procurementMethodPolicy.normalize(
            entity.getSourcingApproach() == null || entity.getSourcingApproach().isBlank()
                ? ProcurementMethodType.OPEN_TENDER.name()
                : entity.getSourcingApproach()
        ));
        entity.setRiskLevel(normalizeEnum(entity.getRiskLevel(), ProcurementRiskLevel.MEDIUM.name(), enumNames(ProcurementRiskLevel.values()), "riskLevel"));
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setTargetSavingsPercent(nvl(entity.getTargetSavingsPercent()));
        entity.setPlannedSpendAmount(nvl(entity.getPlannedSpendAmount()));
        entity.setApprovedSpendLimit(nvl(entity.getApprovedSpendLimit()));
    }
    private void validate(ProcurementCategoryStrategy entity) {
        requireReference(materialGroupRepository.findByIdAndIsDeletedFalse(entity.getMaterialGroupId()).orElse(null), "Material group", entity.getMaterialGroupId());
        if (entity.getBudgetItemId() != null) {
            requireReference(budgetItemRepository.findByIdAndIsDeletedFalse(entity.getBudgetItemId()).orElse(null), "Budget item", entity.getBudgetItemId());
        }
        if (entity.getOwnerEmployeeId() != null) {
            requireReference(employeeRepository.findByIdAndIsDeletedFalse(entity.getOwnerEmployeeId()).orElse(null), "Employee", entity.getOwnerEmployeeId());
        }
        if (entity.getStrategyYear() < 2000 || entity.getStrategyYear() > 2100) {
            throw new BusinessException("strategyYear must be between 2000 and 2100");
        }
        if (entity.getTargetSavingsPercent().signum() < 0 || entity.getPlannedSpendAmount().signum() < 0 || entity.getApprovedSpendLimit().signum() < 0) {
            throw new BusinessException("Procurement strategy amounts and percent cannot be negative");
        }
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("validTo must be greater than or equal to validFrom");
        }
    }
    private void ensureUnique(ProcurementCategoryStrategy entity) {
        repository.findByTenantIdAndCompanyIdAndMaterialGroupIdAndStrategyYearAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getMaterialGroupId(),
                entity.getStrategyYear()
            )
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Procurement category strategy already exists for material group and year");
            });
    }
    private void requireStatus(ProcurementCategoryStrategy strategy, ProcurementStrategyStatus status) {
        if (!status.name().equals(strategy.getStatus())) {
            throw new BusinessException("Only " + status.name() + " procurement category strategy can be processed");
        }
    }
    private void requireReference(BusinessEntity entity, String entityName, UUID id) {
        if (id == null || entity == null || entity.isDeleted() || !TenantContext.currentTenant().equals(entity.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BusinessException(entityName + " not found: " + id);
        }
    }
    private String normalizeEnum(String value, String fallback, String[] allowed, String field) {
        String normalized = normalizeToken(value, fallback);
        if (Arrays.asList(allowed).contains(normalized)) {
            return normalized;
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String normalizeToken(String value, String fallback) {
        String token = value == null || value.isBlank() ? fallback : value;
        return token == null ? null : token.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<ProcurementCategoryStrategyResponse> search(ProcurementCategoryStrategySearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ProcurementCategoryStrategy.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ProcurementCategoryStrategyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ProcurementCategoryStrategyResponse create(ProcurementCategoryStrategyCreateRequest dto) {
        ProcurementCategoryStrategy entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ProcurementCategoryStrategy saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ProcurementCategoryStrategy entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ProcurementCategoryStrategyResponse restore(UUID id) {
        ProcurementCategoryStrategy entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ProcurementCategoryStrategy saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ProcurementCategoryStrategy findEntity(UUID id) {
        ProcurementCategoryStrategy entity = repository.findByIdAndIsDeletedFalse(id)
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
