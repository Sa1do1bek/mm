package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ProcurementPlanResponse;
import com.enterprise.mm.payload.ProcurementPlanUpdateRequest;
import com.enterprise.mm.payload.PriorYearPlanImport;
import com.enterprise.mm.payload.ProcurementPlanCreateRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ProcurementPlanningDtos.ProcurementPlanConsolidationResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProcurementPlanMapper;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.ProcurementPlanStatus;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ProcurementCategoryStrategy;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ConsolidatedDemandRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementCategoryStrategyRepository;
import com.enterprise.mm.repository.ProcurementPlanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ProcurementPlanSearchRequest;

@Service
@RequiredArgsConstructor
public class ProcurementPlanService {
    private final String entityName = "ProcurementPlan";
    private final ProcurementPlanRepository repository;
    private final ProcurementPlanMapper mapper;
    private final ProcurementCategoryStrategyRepository strategyRepository;
    private final DemandPlanningCycleRepository demandPlanningCycleRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final ConsolidatedDemandRepository consolidatedDemandRepository;
    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final BudgetItemRepository budgetItemRepository;
    private final EmployeeRepository employeeRepository;
    private final ProcurementCampaignRepository campaignRepository;
    private final IntegrationEventService integrationEventService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;

    protected void beforeCreate(ProcurementPlan entity, ProcurementPlanCreateRequest dto) {
        hydrate(entity);
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

        if (entity.getPlanNumber() == null || entity.getPlanNumber().isBlank()) {
            entity.setPlanNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(ProcurementPlanStatus.DRAFT.name());
    }
    @Transactional
    public ProcurementPlanResponse update(UUID id, ProcurementPlanUpdateRequest dto) {
        ProcurementPlan current = findEntity(id);
        if (ProcurementPlanStatus.ACTIVE.name().equals(current.getStatus()) || ProcurementPlanStatus.CLOSED.name().equals(current.getStatus()) || ProcurementPlanStatus.CANCELLED.name().equals(current.getStatus())) {
            throw new BusinessException("ACTIVE, CLOSED or CANCELLED procurement plan cannot be edited");
        }
        dto.setStatus(current.getStatus());
        ProcurementPlan entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ProcurementPlan saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected void beforeUpdate(ProcurementPlan entity, ProcurementPlanUpdateRequest dto) {
        hydrate(entity);
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public ProcurementPlanConsolidationResult consolidate(UUID id) {
        ProcurementPlan plan = findEntity(id);
        if (ProcurementPlanStatus.CLOSED.name().equals(plan.getStatus()) || ProcurementPlanStatus.CANCELLED.name().equals(plan.getStatus())) {
            throw new BusinessException("Closed or cancelled procurement plan cannot be consolidated");
        }
        BigDecimal amount = BigDecimal.ZERO;
        int count = 0;
        if (plan.getDemandPlanningCycleId() != null) {
            List<DemandPlan> demandPlans = demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getDemandPlanningCycleId()).stream()
                .filter(demandPlan -> com.enterprise.mm.security.AccessScope.canAccessCompany(demandPlan.getCompanyId()))
                .toList();
            for (DemandPlan demandPlan : demandPlans) {
                for (ConsolidatedDemand demand : consolidatedDemandRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), demandPlan.getId())) {
                    if (!com.enterprise.mm.security.AccessScope.canAccessCompany(demand.getCompanyId()) || !matchesCategory(plan, demand)) {
                        continue;
                    }
                    amount = amount.add(nvl(demand.getTotalEstimatedAmount()));
                    count++;
                }
            }
        }
        if (amount.signum() > 0) {
            plan.setPlannedSpendAmount(amount);
        }
        plan.setDemandCount(count);
        plan.setExpectedSavingsAmount(expectedSavings(plan));
        plan.setStatus(ProcurementPlanStatus.CONSOLIDATING.name());
        auditService.log("ProcurementPlan", id, "CONSOLIDATE", "demands=" + count + ", amount=" + plan.getPlannedSpendAmount(), null, null);
        return new ProcurementPlanConsolidationResult(id, count, mapper.toResponse(repository.save(plan)).getPlannedSpendAmount());
    }
    @Transactional
    public ProcurementPlanResponse submitBudget(UUID id, String comment) {
        ProcurementPlan plan = findEntity(id);
        if (!ProcurementPlanStatus.DRAFT.name().equals(plan.getStatus()) && !ProcurementPlanStatus.CONSOLIDATING.name().equals(plan.getStatus())) {
            throw new BusinessException("Only DRAFT or CONSOLIDATING procurement plan can be submitted to budget");
        }
        if (nvl(plan.getPlannedSpendAmount()).signum() <= 0) {
            throw new BusinessException("Procurement plan has no planned spend amount");
        }
        plan.setStatus(ProcurementPlanStatus.BUDGET_REVIEW.name());
        plan.setSubmittedAt(LocalDateTime.now());
        integrationEventService.emit("BUDGETING.PROCUREMENT_PLAN_SUBMITTED", "ProcurementPlan", id, Map.of(
            "procurementPlanId", id,
            "planYear", plan.getPlanYear(),
            "periodType", plan.getPeriodType(),
            "periodQuarter", plan.getPeriodQuarter() == null ? "" : plan.getPeriodQuarter(),
            "amount", nvl(plan.getPlannedSpendAmount()),
            "currency", plan.getCurrencyCode()
        ));
        auditService.log("ProcurementPlan", id, "SUBMIT_BUDGET", comment, null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public ProcurementPlanResponse approve(UUID id, String comment) {
        ProcurementPlan plan = findEntity(id);
        requireStatus(plan, ProcurementPlanStatus.BUDGET_REVIEW);
        segregationOfDutyRuleService.validate("ProcurementPlan", "APPROVE", plan);
        if (nvl(plan.getApprovedBudgetAmount()).signum() == 0) {
            plan.setApprovedBudgetAmount(nvl(plan.getPlannedSpendAmount()));
        }
        plan.setStatus(ProcurementPlanStatus.APPROVED.name());
        plan.setApprovedAt(LocalDateTime.now());
        integrationEventService.emit("PROCUREMENT.PLAN_APPROVED", "ProcurementPlan", id, Map.of(
            "procurementPlanId", id,
            "approvedBudgetAmount", nvl(plan.getApprovedBudgetAmount()),
            "currency", plan.getCurrencyCode()
        ));
        auditService.log("ProcurementPlan", id, "APPROVE", comment, null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public ProcurementPlanResponse activate(UUID id) {
        ProcurementPlan plan = findEntity(id);
        requireStatus(plan, ProcurementPlanStatus.APPROVED);
        plan.setStatus(ProcurementPlanStatus.ACTIVE.name());
        plan.setActivatedAt(LocalDateTime.now());
        auditService.log("ProcurementPlan", id, "ACTIVATE", plan.getPlanNumber(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public ProcurementPlanResponse close(UUID id) {
        ProcurementPlan plan = findEntity(id);
        if (!ProcurementPlanStatus.ACTIVE.name().equals(plan.getStatus()) && !ProcurementPlanStatus.APPROVED.name().equals(plan.getStatus())) {
            throw new BusinessException("Only ACTIVE or APPROVED procurement plan can be closed");
        }
        plan.setStatus(ProcurementPlanStatus.CLOSED.name());
        plan.setClosedAt(LocalDateTime.now());
        auditService.log("ProcurementPlan", id, "CLOSE", plan.getPlanNumber(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public ProcurementPlanResponse cancel(UUID id) {
        ProcurementPlan plan = findEntity(id);
        if (ProcurementPlanStatus.CLOSED.name().equals(plan.getStatus())) {
            throw new BusinessException("Closed procurement plan cannot be cancelled");
        }
        plan.setStatus(ProcurementPlanStatus.CANCELLED.name());
        auditService.log("ProcurementPlan", id, "CANCEL", plan.getPlanNumber(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public void recalculateCampaignCount(UUID procurementPlanId) {
        if (procurementPlanId == null) {
            return;
        }
        ProcurementPlan plan = repository.findByIdAndIsDeletedFalse(procurementPlanId)
            .filter(existing -> !existing.isDeleted() && TenantContext.currentTenant().equals(existing.getTenantId()) && com.enterprise.mm.security.AccessScope.canAccessCompany(existing.getCompanyId()))
            .orElse(null);
        if (plan == null) {
            return;
        }
        plan.setCampaignCount(campaignRepository.findByTenantIdAndCompanyIdAndProcurementPlanIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), procurementPlanId).size());
        repository.save(plan);
    }
    private void hydrate(ProcurementPlan entity) {
        if (entity.getCategoryStrategyId() != null) {
            ProcurementCategoryStrategy strategy = requireStrategy(entity.getCategoryStrategyId());
            if (entity.getMaterialGroupId() == null) {
                entity.setMaterialGroupId(strategy.getMaterialGroupId());
            }
            if (entity.getBudgetItemId() == null) {
                entity.setBudgetItemId(strategy.getBudgetItemId());
            }
            if (entity.getOwnerEmployeeId() == null) {
                entity.setOwnerEmployeeId(strategy.getOwnerEmployeeId());
            }
            if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
                entity.setCurrencyCode(strategy.getCurrencyCode());
            }
            if (nvl(entity.getPlannedSpendAmount()).signum() == 0) {
                entity.setPlannedSpendAmount(nvl(strategy.getPlannedSpendAmount()));
            }
            if (nvl(entity.getApprovedBudgetAmount()).signum() == 0) {
                entity.setApprovedBudgetAmount(nvl(strategy.getApprovedSpendLimit()));
            }
        }
        if (entity.getDemandPlanningCycleId() != null) {
            DemandPlanningCycle cycle = requireCycle(entity.getDemandPlanningCycleId());
            entity.setPlanYear(cycle.getPlanYear());
            entity.setPeriodType(cycle.getPeriodType());
            entity.setPeriodQuarter(cycle.getQuarter());
            if (entity.getPlanStartDate() == null) {
                entity.setPlanStartDate(cycle.getPeriodStart());
            }
            if (entity.getPlanEndDate() == null) {
                entity.setPlanEndDate(cycle.getPeriodEnd());
            }
            if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
                entity.setCurrencyCode(cycle.getCurrencyCode());
            }
        }
    }
    private void normalize(ProcurementPlan entity) {
        if (entity.getPlanYear() == null) {
            entity.setPlanYear(Year.now().getValue());
        }
        entity.setPeriodType(normalizeEnum(entity.getPeriodType(), DemandPeriodType.YEAR.name(), enumNames(DemandPeriodType.values()), "periodType"));
        if (DemandPeriodType.YEAR.name().equals(entity.getPeriodType())) {
            entity.setPeriodQuarter(null);
        }
        if (entity.getPlanStartDate() == null || entity.getPlanEndDate() == null) {
            applyDefaultPeriodDates(entity);
        }
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setPlannedSpendAmount(nvl(entity.getPlannedSpendAmount()));
        entity.setApprovedBudgetAmount(nvl(entity.getApprovedBudgetAmount()));
        entity.setReservedBudgetAmount(nvl(entity.getReservedBudgetAmount()));
        entity.setExpectedSavingsAmount(nvl(entity.getExpectedSavingsAmount()));
        entity.setDemandCount(entity.getDemandCount() == null ? 0 : entity.getDemandCount());
        entity.setCampaignCount(entity.getCampaignCount() == null ? 0 : entity.getCampaignCount());
        if (entity.getPlanNumber() != null) {
            entity.setPlanNumber(entity.getPlanNumber().trim());
        }
    }
    private void validate(ProcurementPlan entity) {
        if (entity.getPlanYear() < 2000 || entity.getPlanYear() > 2100) {
            throw new BusinessException("planYear must be between 2000 and 2100");
        }
        if (DemandPeriodType.QUARTER.name().equals(entity.getPeriodType()) && (entity.getPeriodQuarter() == null || entity.getPeriodQuarter() < 1 || entity.getPeriodQuarter() > 4)) {
            throw new BusinessException("periodQuarter must be between 1 and 4 for QUARTER plan");
        }
        if (entity.getMaterialGroupId() != null) {
            requireReference(materialGroupRepository.findByIdAndIsDeletedFalse(entity.getMaterialGroupId()).orElse(null), "Material group", entity.getMaterialGroupId());
        }
        if (entity.getBudgetItemId() != null) {
            requireReference(budgetItemRepository.findByIdAndIsDeletedFalse(entity.getBudgetItemId()).orElse(null), "Budget item", entity.getBudgetItemId());
        }
        if (entity.getOwnerEmployeeId() != null) {
            requireReference(employeeRepository.findByIdAndIsDeletedFalse(entity.getOwnerEmployeeId()).orElse(null), "Employee", entity.getOwnerEmployeeId());
        }
        if (entity.getPlanStartDate() != null && entity.getPlanEndDate() != null && entity.getPlanEndDate().isBefore(entity.getPlanStartDate())) {
            throw new BusinessException("planEndDate must be greater than or equal to planStartDate");
        }
        if (entity.getPlannedSpendAmount().signum() < 0 || entity.getApprovedBudgetAmount().signum() < 0 || entity.getReservedBudgetAmount().signum() < 0 || entity.getExpectedSavingsAmount().signum() < 0) {
            throw new BusinessException("Procurement plan amounts cannot be negative");
        }
    }
    private void ensureUnique(ProcurementPlan entity) {
        if (entity.getPlanNumber() == null || entity.getPlanNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndPlanNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getPlanNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Procurement plan number already exists");
            });
    }
    private boolean matchesCategory(ProcurementPlan plan, ConsolidatedDemand demand) {
        if (plan.getMaterialGroupId() == null) {
            return true;
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), demand.getMaterialId()).orElse(null);
        return material != null && com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()) && plan.getMaterialGroupId().equals(material.getMaterialGroupId());
    }
    private BigDecimal expectedSavings(ProcurementPlan plan) {
        if (plan.getCategoryStrategyId() == null) {
            return nvl(plan.getExpectedSavingsAmount());
        }
        ProcurementCategoryStrategy strategy = requireStrategy(plan.getCategoryStrategyId());
        if (nvl(strategy.getTargetSavingsPercent()).signum() == 0 || nvl(plan.getPlannedSpendAmount()).signum() == 0) {
            return nvl(plan.getExpectedSavingsAmount());
        }
        return nvl(plan.getPlannedSpendAmount())
            .multiply(strategy.getTargetSavingsPercent())
            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
    private ProcurementCategoryStrategy requireStrategy(UUID id) {
        return strategyRepository.findByIdAndIsDeletedFalse(id)
            .filter(existing -> !existing.isDeleted() && TenantContext.currentTenant().equals(existing.getTenantId()) && com.enterprise.mm.security.AccessScope.canAccessCompany(existing.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Procurement category strategy not found: " + id));
    }
    private DemandPlanningCycle requireCycle(UUID id) {
        return demandPlanningCycleRepository.findByIdAndIsDeletedFalse(id)
            .filter(existing -> !existing.isDeleted() && TenantContext.currentTenant().equals(existing.getTenantId()) && com.enterprise.mm.security.AccessScope.canAccessCompany(existing.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Demand planning cycle not found: " + id));
    }
    private void requireStatus(ProcurementPlan plan, ProcurementPlanStatus status) {
        if (!status.name().equals(plan.getStatus())) {
            throw new BusinessException("Only " + status.name() + " procurement plan can be processed");
        }
    }
    private void requireReference(BusinessEntity entity, String entityName, UUID id) {
        if (id == null || entity == null || entity.isDeleted() || !TenantContext.currentTenant().equals(entity.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BusinessException(entityName + " not found: " + id);
        }
    }
    private void applyDefaultPeriodDates(ProcurementPlan entity) {
        if (DemandPeriodType.YEAR.name().equals(entity.getPeriodType())) {
            entity.setPlanStartDate(LocalDate.of(entity.getPlanYear(), 1, 1));
            entity.setPlanEndDate(LocalDate.of(entity.getPlanYear(), 12, 31));
            return;
        }
        int quarter = entity.getPeriodQuarter() == null ? 1 : entity.getPeriodQuarter();
        int startMonth = ((quarter - 1) * 3) + 1;
        LocalDate start = LocalDate.of(entity.getPlanYear(), startMonth, 1);
        entity.setPlanStartDate(start);
        entity.setPlanEndDate(start.plusMonths(3).minusDays(1));
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
    public Page<ProcurementPlanResponse> search(ProcurementPlanSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ProcurementPlan.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ProcurementPlanResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ProcurementPlanResponse create(ProcurementPlanCreateRequest dto) {
        ProcurementPlan entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ProcurementPlan saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public Map<String, Integer> importPriorYear(PriorYearPlanImport request) {
        if (request == null || request.year() == null) {
            throw new BadRequestException("Prior-year plan year is required");
        }
        int created = 0;
        int updated = 0;
        for (PriorYearPlanImport.Line line : request.lines() == null ? List.<PriorYearPlanImport.Line>of() : request.lines()) {
            if (line == null || line.planNumber() == null || line.planNumber().isBlank()) {
                throw new BadRequestException("Prior-year plan number is required");
            }
            String planNumber = line.planNumber().trim();
            var existing = repository.findByTenantIdAndCompanyIdAndPlanNumberAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), planNumber);
            if (existing.isPresent()) {
                ProcurementPlan plan = existing.get();
                plan.setPlanYear(request.year());
                plan.setPlannedSpendAmount(line.plannedSpendAmount() == null ? BigDecimal.ZERO : line.plannedSpendAmount());
                if (line.name() != null && !line.name().isBlank()) {
                    plan.setName(line.name().trim());
                }
                if (line.materialGroupId() != null) {
                    plan.setMaterialGroupId(line.materialGroupId());
                }
                if (line.currencyCode() != null && !line.currencyCode().isBlank()) {
                    plan.setCurrencyCode(line.currencyCode().trim().toUpperCase(Locale.ROOT));
                }
                plan.setDescription("Imported prior-year purchase plan");
                repository.save(plan);
                updated++;
            } else {
                ProcurementPlanCreateRequest dto = new ProcurementPlanCreateRequest();
                dto.setPlanNumber(planNumber);
                dto.setName(line.name() == null || line.name().isBlank() ? planNumber : line.name().trim());
                dto.setPlanYear(request.year());
                dto.setMaterialGroupId(line.materialGroupId());
                dto.setPlannedSpendAmount(line.plannedSpendAmount() == null ? BigDecimal.ZERO : line.plannedSpendAmount());
                dto.setCurrencyCode(line.currencyCode());
                dto.setDescription("Imported prior-year purchase plan");
                dto.setPeriodType("YEAR");
                create(dto);
                created++;
            }
        }
        return Map.of("created", created, "updated", updated, "imported", created + updated);
    }
    @Transactional
    public void softDelete(UUID id) {
        ProcurementPlan entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ProcurementPlanResponse restore(UUID id) {
        ProcurementPlan entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ProcurementPlan saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ProcurementPlan findEntity(UUID id) {
        ProcurementPlan entity = repository.findByIdAndIsDeletedFalse(id)
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

