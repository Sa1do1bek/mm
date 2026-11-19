package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.DemandManagementDtos.DemandActionRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandCyclePlanGenerationRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandCyclePlanGenerationResult;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.payload.DemandPlanningCycleUpdateRequest;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DemandPlanningCycleMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.DemandPlanningCycleApprovalAdapter;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.DemandPlanningCycleSearchRequest;

@Service
public class DemandPlanningCycleService {
    private final DemandPlanningCycleRepository repository;
    private final DemandPlanningCycleMapper mapper;
    private final String entityName = "DemandPlanningCycle";
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final BudgetItemService budgetItemService;
    private final RoleDataScopeService roleDataScopeService;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final ApprovalEngineService approvalEngineService;
    private final DemandPlanNamingService demandPlanNamingService;
    public DemandPlanningCycleService(
        DemandPlanningCycleRepository repository,
        DemandPlanningCycleMapper mapper,
        DemandPlanRepository demandPlanRepository,
        DemandPlanItemRepository demandPlanItemRepository,
        IntegrationEventService integrationEventService,
        AuditService auditService,
        EntityManager entityManager,
        BudgetItemService budgetItemService,
        RoleDataScopeService roleDataScopeService,
        EmployeeRepository employeeRepository,
        UserRepository userRepository,
        ApprovalEngineService approvalEngineService,
        DemandPlanNamingService demandPlanNamingService
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.demandPlanRepository = demandPlanRepository;
        this.demandPlanItemRepository = demandPlanItemRepository;
        this.integrationEventService = integrationEventService;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.budgetItemService = budgetItemService;
        this.roleDataScopeService = roleDataScopeService;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.approvalEngineService = approvalEngineService;
        this.demandPlanNamingService = demandPlanNamingService;
    }
    protected void beforeCreate(DemandPlanningCycle entity, DemandPlanningCycleCreateRequest dto) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus(DemandPlanningCycleStatus.DRAFT.name());
        normalize(entity);
    }
    protected void beforeUpdate(DemandPlanningCycle entity, DemandPlanningCycleUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public DemandPlanningCycleResponse update(UUID id, DemandPlanningCycleUpdateRequest dto) {
        DemandPlanningCycle current = findEntity(id);
        if (!DemandPlanningCycleStatus.DRAFT.name().equals(current.getStatus())
            && !DemandPlanningCycleStatus.COLLECTING.name().equals(current.getStatus())) {
            throw new BusinessException(
                "Demand planning cycle must be DRAFT or COLLECTING to be edited, current status is " + current.getStatus()
            );
        }

        boolean identityLocked = !linkedPlans(id).isEmpty()
            || DemandPlanningCycleStatus.COLLECTING.name().equals(current.getStatus());
        if (identityLocked) {
            assertIdentityUnchanged(dto, current);
        }

        DemandPlanningCycleUpdateRequest effective = nullSafePatch(dto, current, identityLocked);
        DemandPlanningCycle entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        applyEffectiveUpdate(entity, effective, current);
        if (effective.getOwnerEmployeeId() != null) {
            applyOwnerEmployee(entity, effective.getOwnerEmployeeId());
        }
        beforeUpdate(entity, effective);
        DemandPlanningCycle saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    private DemandPlanningCycleUpdateRequest nullSafePatch(
        DemandPlanningCycleUpdateRequest dto,
        DemandPlanningCycle current,
        boolean identityLocked
    ) {
        DemandPlanningCycleUpdateRequest effective = new DemandPlanningCycleUpdateRequest();
        effective.setCode(current.getCode());
        effective.setName(firstNonBlank(dto.getName(), current.getName()));
        effective.setDescription(dto.getDescription() != null ? dto.getDescription() : current.getDescription());
        effective.setPlanYear(identityLocked
            ? current.getPlanYear()
            : (dto.getPlanYear() != null ? dto.getPlanYear() : current.getPlanYear()));
        effective.setPeriodType(identityLocked
            ? current.getPeriodType()
            : firstNonBlank(dto.getPeriodType(), current.getPeriodType()));
        effective.setQuarter(identityLocked
            ? current.getQuarter()
            : (dto.getQuarter() != null ? dto.getQuarter() : current.getQuarter()));
        effective.setPeriodStart(identityLocked
            ? current.getPeriodStart()
            : (dto.getPeriodStart() != null ? dto.getPeriodStart() : current.getPeriodStart()));
        effective.setPeriodEnd(identityLocked
            ? current.getPeriodEnd()
            : (dto.getPeriodEnd() != null ? dto.getPeriodEnd() : current.getPeriodEnd()));
        effective.setCollectionStartDate(
            dto.getCollectionStartDate() != null ? dto.getCollectionStartDate() : current.getCollectionStartDate()
        );
        effective.setCollectionEndDate(
            dto.getCollectionEndDate() != null ? dto.getCollectionEndDate() : current.getCollectionEndDate()
        );
        effective.setBudgetReviewDeadline(
            dto.getBudgetReviewDeadline() != null ? dto.getBudgetReviewDeadline() : current.getBudgetReviewDeadline()
        );
        effective.setConsolidationDeadline(
            dto.getConsolidationDeadline() != null ? dto.getConsolidationDeadline() : current.getConsolidationDeadline()
        );
        effective.setActivationDate(
            dto.getActivationDate() != null ? dto.getActivationDate() : current.getActivationDate()
        );
        effective.setOwnerEmployeeId(dto.getOwnerEmployeeId());
        effective.setCurrencyCode(identityLocked
            ? current.getCurrencyCode()
            : firstNonBlank(dto.getCurrencyCode(), current.getCurrencyCode()));
        return effective;
    }

    private void applyEffectiveUpdate(
        DemandPlanningCycle entity,
        DemandPlanningCycleUpdateRequest effective,
        DemandPlanningCycle current
    ) {
        entity.setCode(current.getCode());
        entity.setStatus(current.getStatus());
        entity.setName(effective.getName());
        entity.setDescription(effective.getDescription());
        entity.setPlanYear(effective.getPlanYear());
        entity.setPeriodType(effective.getPeriodType());
        entity.setQuarter(effective.getQuarter());
        entity.setPeriodStart(effective.getPeriodStart());
        entity.setPeriodEnd(effective.getPeriodEnd());
        entity.setCollectionStartDate(effective.getCollectionStartDate());
        entity.setCollectionEndDate(effective.getCollectionEndDate());
        entity.setBudgetReviewDeadline(effective.getBudgetReviewDeadline());
        entity.setConsolidationDeadline(effective.getConsolidationDeadline());
        entity.setActivationDate(effective.getActivationDate());
        entity.setOwnerUsername(current.getOwnerUsername());
        entity.setCurrencyCode(effective.getCurrencyCode());
        entity.setTotalPlanCount(current.getTotalPlanCount());
        entity.setTotalItemCount(current.getTotalItemCount());
        entity.setTotalPlannedAmount(current.getTotalPlannedAmount());
        entity.setTotalApprovedAmount(current.getTotalApprovedAmount());
        entity.setTotalReservedQuantity(current.getTotalReservedQuantity());
        entity.setGeneratedAt(current.getGeneratedAt());
        entity.setSubmittedAt(current.getSubmittedAt());
        entity.setApprovedAt(current.getApprovedAt());
        entity.setActivatedAt(current.getActivatedAt());
        entity.setClosedAt(current.getClosedAt());
    }

    private void assertIdentityUnchanged(DemandPlanningCycleUpdateRequest dto, DemandPlanningCycle current) {
        if (dto.getPlanYear() != null && !Objects.equals(dto.getPlanYear(), current.getPlanYear())) {
            throw new BusinessException(
                "planYear cannot be changed after plans exist or collection has started"
            );
        }
        if (!blank(dto.getPeriodType())
            && !dto.getPeriodType().trim().equalsIgnoreCase(Objects.toString(current.getPeriodType(), ""))) {
            throw new BusinessException(
                "periodType cannot be changed after plans exist or collection has started"
            );
        }
        if (dto.getQuarter() != null && !Objects.equals(dto.getQuarter(), current.getQuarter())) {
            throw new BusinessException(
                "quarter cannot be changed after plans exist or collection has started"
            );
        }
        if (!blank(dto.getCurrencyCode())
            && !dto.getCurrencyCode().trim().equalsIgnoreCase(Objects.toString(current.getCurrencyCode(), ""))) {
            throw new BusinessException(
                "currencyCode cannot be changed after plans exist or collection has started"
            );
        }
    }

    private String firstNonBlank(String candidate, String fallback) {
        return blank(candidate) ? fallback : candidate;
    }
    @Transactional
    public DemandPlanningCycleResponse startCollection(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        requireStatus(cycle, DemandPlanningCycleStatus.DRAFT);
        cycle.setStatus(DemandPlanningCycleStatus.COLLECTING.name());
        cycle.setCollectionStartDate(Objects.requireNonNullElse(cycle.getCollectionStartDate(), LocalDate.now()));
        auditService.log("DemandPlanningCycle", id, "START_COLLECTION", cycle.getCode(), null, null);
        return mapper.toResponse(repository.save(cycle));
    }
    @Transactional
    public DemandPlanningCycleResponse closeCollection(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        requireStatus(cycle, DemandPlanningCycleStatus.COLLECTING);
        cycle.setStatus(DemandPlanningCycleStatus.COLLECTION_CLOSED.name());
        cycle.setCollectionEndDate(Objects.requireNonNullElse(cycle.getCollectionEndDate(), LocalDate.now()));
        repository.save(cycle);
        recalculateCycleTotals(id);
        auditService.log("DemandPlanningCycle", id, "CLOSE_COLLECTION", cycle.getCode(), null, null);
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DemandPlanningCycleResponse submitBudget(UUID id, DemandActionRequest request) {
        validateBudgetSubmission(id);
        approvalEngineService.start(DemandPlanningCycleApprovalAdapter.MODULE_KEY, id);
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public DemandPlanningCycleResponse approveBudget(UUID id, DemandActionRequest request) {
        throw new BusinessException("Demand planning cycle budget approval must be completed through approval task endpoints");
    }

    @Transactional
    public void validateBudgetSubmission(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        if (!DemandPlanningCycleStatus.COLLECTION_CLOSED.name().equals(cycle.getStatus())
            && !DemandPlanningCycleStatus.COLLECTING.name().equals(cycle.getStatus())) {
            throw new BusinessException("Only COLLECTING or COLLECTION_CLOSED planning cycle can be submitted to budget");
        }
        recalculateCycleTotals(id);
        cycle = findEntity(id);
        if (nvl(cycle.getTotalPlannedAmount()).signum() <= 0) {
            throw new BusinessException("Planning cycle has no planned demand amount");
        }
    }

    @Transactional
    public void applyBudgetSubmitted(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        if (DemandPlanningCycleStatus.BUDGET_REVIEW.name().equals(cycle.getStatus())) {
            return;
        }
        if (!DemandPlanningCycleStatus.COLLECTION_CLOSED.name().equals(cycle.getStatus())
            && !DemandPlanningCycleStatus.COLLECTING.name().equals(cycle.getStatus())) {
            throw new BusinessException(
                "Only COLLECTING or COLLECTION_CLOSED planning cycle can be submitted to budget, current status is "
                    + cycle.getStatus()
            );
        }
        recalculateCycleTotals(id);
        cycle = findEntity(id);
        if (nvl(cycle.getTotalPlannedAmount()).signum() <= 0) {
            throw new BusinessException("Planning cycle has no planned demand amount");
        }
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_REVIEW.name());
        cycle.setSubmittedAt(LocalDateTime.now());
        integrationEventService.emit("BUDGETING.DEMAND_CYCLE_SUBMITTED", "DemandPlanningCycle", cycle.getId(), Map.of(
            "planningCycleId", cycle.getId(),
            "planYear", cycle.getPlanYear(),
            "periodType", cycle.getPeriodType(),
            "quarter", cycle.getQuarter() == null ? "" : cycle.getQuarter(),
            "amount", nvl(cycle.getTotalPlannedAmount()),
            "currency", currency(cycle)
        ));
        auditService.log("DemandPlanningCycle", id, "SUBMIT_BUDGET", cycle.getCode(), null, null);
        repository.save(cycle);
    }

    @Transactional
    public void completeBudgetApprovalFromWorkflow(UUID id, String comment) {
        DemandPlanningCycle cycle = findEntity(id);
        requireStatus(cycle, DemandPlanningCycleStatus.BUDGET_REVIEW);
        for (DemandPlan plan : linkedPlans(id)) {
            if (DemandPlanStatus.SUBMITTED.name().equals(plan.getStatus())) {
                approveSubmittedItems(plan);
                plan.setStatus(DemandPlanStatus.APPROVED.name());
                plan.setApprovedAt(LocalDateTime.now());
                demandPlanRepository.save(plan);
            }
        }
        recalculateCycleTotals(id);
        cycle = findEntity(id);
        allocateApprovedBudget(id);
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setApprovedAt(LocalDateTime.now());
        integrationEventService.emit("BUDGETING.DEMAND_CYCLE_APPROVED", "DemandPlanningCycle", cycle.getId(), Map.of(
            "planningCycleId", cycle.getId(),
            "amount", nvl(cycle.getTotalApprovedAmount()),
            "currency", currency(cycle)
        ));
        auditService.log("DemandPlanningCycle", id, "APPROVE_BUDGET", comment, null, null);
        repository.save(cycle);
    }

    @Transactional
    public void rejectBudgetFromWorkflow(UUID id, String comment) {
        DemandPlanningCycle cycle = findEntity(id);
        requireStatus(cycle, DemandPlanningCycleStatus.BUDGET_REVIEW);
        cycle.setStatus(DemandPlanningCycleStatus.COLLECTION_CLOSED.name());
        cycle.setSubmittedAt(null);
        repository.save(cycle);
        auditService.log("DemandPlanningCycle", id, "REJECT_BUDGET", comment, null, null);
    }
    @Transactional
    public DemandPlanningCycleResponse activate(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        requireStatus(cycle, DemandPlanningCycleStatus.BUDGET_APPROVED);
        for (DemandPlan plan : linkedPlans(id)) {
            if (DemandPlanStatus.APPROVED.name().equals(plan.getStatus())) {
                plan.setStatus(DemandPlanStatus.ACTIVE.name());
                demandPlanRepository.save(plan);
            }
        }
        cycle.setStatus(DemandPlanningCycleStatus.ACTIVE.name());
        cycle.setActivatedAt(LocalDateTime.now());
        auditService.log("DemandPlanningCycle", id, "ACTIVATE", cycle.getCode(), null, null);
        return mapper.toResponse(repository.save(cycle));
    }
    @Transactional
    public DemandPlanningCycleResponse close(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        if (!DemandPlanningCycleStatus.ACTIVE.name().equals(cycle.getStatus()) && !DemandPlanningCycleStatus.BUDGET_APPROVED.name().equals(cycle.getStatus())) {
            throw new BusinessException("Only ACTIVE or BUDGET_APPROVED planning cycle can be closed");
        }
        cycle.setStatus(DemandPlanningCycleStatus.CLOSED.name());
        cycle.setClosedAt(LocalDateTime.now());
        auditService.log("DemandPlanningCycle", id, "CLOSE", cycle.getCode(), null, null);
        return mapper.toResponse(repository.save(cycle));
    }
    @Transactional
    public DemandPlanningCycleResponse cancel(UUID id) {
        DemandPlanningCycle cycle = findEntity(id);
        if (DemandPlanningCycleStatus.CLOSED.name().equals(cycle.getStatus())) {
            throw new BusinessException("Closed planning cycle cannot be cancelled");
        }
        cycle.setStatus(DemandPlanningCycleStatus.CANCELLED.name());
        auditService.log("DemandPlanningCycle", id, "CANCEL", cycle.getCode(), null, null);
        return mapper.toResponse(repository.save(cycle));
    }
    @Transactional
    public DemandCyclePlanGenerationResult generatePlans(UUID id, DemandCyclePlanGenerationRequest request) {
        DemandPlanningCycle cycle = findEntity(id);
        if (!DemandPlanningCycleStatus.DRAFT.name().equals(cycle.getStatus()) && !DemandPlanningCycleStatus.COLLECTING.name().equals(cycle.getStatus())) {
            throw new BusinessException("Plans can be generated only for DRAFT or COLLECTING planning cycle");
        }
        Set<UUID> requestedDepartments = new LinkedHashSet<>(request == null || request.departmentIds() == null ? List.of() : request.departmentIds());
        if (requestedDepartments.isEmpty()) {
            UUID scopedDepartmentId = roleDataScopeService.currentScope().departmentId();
            if (scopedDepartmentId == null) {
                throw new BusinessException("Demand plan department is required for generated name");
            }
            requestedDepartments.add(scopedDepartmentId);
        }
        UUID budgetItemId = request == null ? null : request.budgetItemId();
        String ownerUsername = request == null || blank(request.ownerUsername()) ? cycle.getOwnerUsername() : request.ownerUsername();
        List<DemandPlan> existing = linkedPlans(id);
        List<UUID> ids = new ArrayList<>();
        int existingCount = 0;
        int createdCount = 0;
        for (UUID departmentId : requestedDepartments) {
            roleDataScopeService.assertDepartmentDocumentMutationAllowed(departmentId, "Demand plan");
            DemandPlan existingPlan = existing.stream()
                .filter(plan -> Objects.equals(idOf(plan.getDepartment()), departmentId) && Objects.equals(idOf(plan.getBudgetItem()), budgetItemId))
                .findFirst()
                .orElse(null);
            if (existingPlan != null) {
                existingCount++;
                ids.add(existingPlan.getId());
                continue;
            }
            DemandPlan plan = new DemandPlan();
            plan.setPlanningCycle(cycle);
            plan.setCode(planCode(cycle, departmentId, budgetItemId));
            plan.setStatus(DemandPlanStatus.DRAFT.name());
            plan.setPlanYear(cycle.getPlanYear());
            plan.setPeriodType(cycle.getPeriodType());
            plan.setQuarter(cycle.getQuarter());
            plan.setPeriodStart(cycle.getPeriodStart());
            plan.setPeriodEnd(cycle.getPeriodEnd());
            plan.setDepartment(departmentReference(departmentId));
            plan.setBudgetItem(budgetItemReference(budgetItemId));
            plan.setOwnerUsername(ownerUsername);
            plan.setCurrencyCode(currency(cycle));
            demandPlanNamingService.applyCanonicalName(plan);
            plan = demandPlanRepository.save(plan);
            ids.add(plan.getId());
            createdCount++;
        }
        cycle.setGeneratedAt(LocalDateTime.now());
        repository.save(cycle);
        recalculateCycleTotals(id);
        auditService.log("DemandPlanningCycle", id, "GENERATE_PLANS", "created=" + createdCount + ", existing=" + existingCount, null, null);
        return new DemandCyclePlanGenerationResult(id, createdCount, existingCount, ids);
    }
    @Transactional
    public void recalculateCycleTotals(UUID planningCycleId) {
        if (planningCycleId == null) {
            return;
        }
        DemandPlanningCycle cycle = repository.findByIdAndIsDeletedFalse(planningCycleId)
            .filter(existing -> !existing.isDeleted() && TenantContext.currentTenant().equals(existing.getTenantId()))
            .orElse(null);
        if (cycle == null) {
            return;
        }
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        int itemCount = 0;
        List<DemandPlan> plans = linkedPlans(planningCycleId);
        for (DemandPlan plan : plans) {
            planned = planned.add(nvl(plan.getTotalPlannedAmount()));
            approved = approved.add(nvl(plan.getTotalApprovedAmount()));
            reserved = reserved.add(nvl(plan.getTotalReservedQuantity()));
            itemCount += demandPlanItemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId()).size();
        }
        cycle.setTotalPlanCount(plans.size());
        cycle.setTotalItemCount(itemCount);
        cycle.setTotalPlannedAmount(planned);
        cycle.setTotalApprovedAmount(approved);
        cycle.setTotalReservedQuantity(reserved);
        repository.save(cycle);
    }
    private void normalize(DemandPlanningCycle cycle) {
        if (cycle.getPlanYear() == null) {
            cycle.setPlanYear(Year.now().getValue());
        }
        if (blank(cycle.getPeriodType())) {
            cycle.setPeriodType(DemandPeriodType.YEAR.name());
        }
        DemandPeriodType periodType = DemandPeriodType.valueOf(cycle.getPeriodType());
        if (periodType == DemandPeriodType.QUARTER) {
            if (cycle.getQuarter() == null || cycle.getQuarter() < 1 || cycle.getQuarter() > 4) {
                throw new BusinessException("Quarter must be between 1 and 4");
            }
            int startMonth = (cycle.getQuarter() - 1) * 3 + 1;
            cycle.setPeriodStart(LocalDate.of(cycle.getPlanYear(), startMonth, 1));
            cycle.setPeriodEnd(cycle.getPeriodStart().plusMonths(3).minusDays(1));
        } else {
            cycle.setQuarter(null);
            cycle.setPeriodStart(LocalDate.of(cycle.getPlanYear(), 1, 1));
            cycle.setPeriodEnd(LocalDate.of(cycle.getPlanYear(), 12, 31));
        }
        if (cycle.getCollectionStartDate() == null) {
            cycle.setCollectionStartDate(LocalDate.now());
        }
        if (cycle.getCollectionEndDate() == null) {
            LocalDate defaultEnd = cycle.getPeriodStart().minusDays(30);
            cycle.setCollectionEndDate(defaultEnd.isBefore(cycle.getCollectionStartDate()) ? cycle.getCollectionStartDate().plusDays(14) : defaultEnd);
        }
        if (cycle.getBudgetReviewDeadline() == null) {
            cycle.setBudgetReviewDeadline(cycle.getCollectionEndDate().plusDays(10));
        }
        if (cycle.getConsolidationDeadline() == null) {
            cycle.setConsolidationDeadline(cycle.getBudgetReviewDeadline().plusDays(5));
        }
        if (cycle.getActivationDate() == null) {
            cycle.setActivationDate(cycle.getPeriodStart());
        }
        if (cycle.getCollectionEndDate().isBefore(cycle.getCollectionStartDate())) {
            throw new BusinessException("Collection end date cannot be before collection start date");
        }
        if (cycle.getBudgetReviewDeadline().isBefore(cycle.getCollectionEndDate())) {
            throw new BusinessException("Budget review deadline cannot be before collection end date");
        }
        if (cycle.getConsolidationDeadline().isBefore(cycle.getBudgetReviewDeadline())) {
            throw new BusinessException("Consolidation deadline cannot be before budget review deadline");
        }
        if (blank(cycle.getCurrencyCode())) {
            cycle.setCurrencyCode("UZS");
        }
        cycle.setTotalPlanCount(Objects.requireNonNullElse(cycle.getTotalPlanCount(), 0));
        cycle.setTotalItemCount(Objects.requireNonNullElse(cycle.getTotalItemCount(), 0));
        cycle.setTotalPlannedAmount(nvl(cycle.getTotalPlannedAmount()));
        cycle.setTotalApprovedAmount(nvl(cycle.getTotalApprovedAmount()));
        cycle.setTotalReservedQuantity(nvl(cycle.getTotalReservedQuantity()));
    }
    private List<DemandPlan> linkedPlans(UUID planningCycleId) {
        return demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TenantContext.currentTenant(), planningCycleId);
    }

    private void approveSubmittedItems(DemandPlan plan) {
        for (DemandPlanItem item : demandPlanItemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId())) {
            if (DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus()) || DemandPlanItemStatus.DRAFT.name().equals(item.getStatus())) {
                item.setStatus(DemandPlanItemStatus.APPROVED.name());
                if (nvl(item.getApprovedQuantity()).signum() == 0) {
                    item.setApprovedQuantity(nvl(item.getRequestedQuantity()));
                }
                if (nvl(item.getEstimatedAmount()).signum() == 0) {
                    item.setEstimatedAmount(nvl(item.getApprovedQuantity()).multiply(nvl(item.getEstimatedUnitPrice())));
                }
                demandPlanItemRepository.save(item);
            }
        }
    }

    private void allocateApprovedBudget(UUID planningCycleId) {
        java.util.Set<UUID> budgetItemIds = new java.util.HashSet<>();
        for (DemandPlan plan : linkedPlans(planningCycleId)) {
            if (!DemandPlanStatus.APPROVED.name().equals(plan.getStatus())
                && !DemandPlanStatus.ACTIVE.name().equals(plan.getStatus())) {
                continue;
            }
            for (DemandPlanItem item : demandPlanItemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId())) {
                if (item.getBudgetItemId() == null || !isBudgetApprovedItem(item)) {
                    continue;
                }
                budgetItemIds.add(item.getBudgetItemId());
            }
        }
        budgetItemIds.forEach(budgetItemId ->
            budgetItemService.ensureAnnualLimitAtLeast(budgetItemId, totalApprovedAmount(budgetItemId), "DemandPlanningCycle " + planningCycleId)
        );
    }

    private BigDecimal totalApprovedAmount(UUID budgetItemId) {
        return demandPlanItemRepository.findByTenantIdAndBudgetItemIdAndIsDeletedFalse(TenantContext.currentTenant(), budgetItemId)
            .stream()
            .filter(this::isBudgetApprovedItem)
            .map(this::approvedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isBudgetApprovedItem(DemandPlanItem item) {
        return DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(item.getStatus());
    }

    private BigDecimal approvedAmount(DemandPlanItem item) {
        BigDecimal amount = nvl(item.getEstimatedAmount());
        if (amount.signum() == 0) {
            amount = nvl(item.getApprovedQuantity()).multiply(nvl(item.getEstimatedUnitPrice()));
        }
        return amount;
    }

    private void requireStatus(DemandPlanningCycle cycle, DemandPlanningCycleStatus status) {
        if (!status.name().equals(cycle.getStatus())) {
            throw new BusinessException("Planning cycle must be " + status + ", current status is " + cycle.getStatus());
        }
    }
    private String planCode(DemandPlanningCycle cycle, UUID departmentId, UUID budgetItemId) {
        String period = DemandPeriodType.QUARTER.name().equals(cycle.getPeriodType()) ? "Q" + cycle.getQuarter() : "Y";
        String departmentToken = departmentId == null ? "ALL" : departmentId.toString().substring(0, 8).toUpperCase();
        String budgetToken = budgetItemId == null ? "GEN" : budgetItemId.toString().substring(0, 8).toUpperCase();
        return "DP-" + cycle.getPlanYear() + "-" + period + "-" + departmentToken + "-" + budgetToken;
    }
    private UUID idOf(com.enterprise.mm.common.BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
    private Department departmentReference(UUID id) {
        if (id == null) {
            return null;
        }
        return entityManager.getReference(Department.class, id);
    }
    private BudgetItem budgetItemReference(UUID id) {
        if (id == null) {
            return null;
        }
        return entityManager.getReference(BudgetItem.class, id);
    }
    private String currency(DemandPlanningCycle cycle) {
        return blank(cycle.getCurrencyCode()) ? "UZS" : cycle.getCurrencyCode();
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
@Transactional(readOnly = true)
    public Page<DemandPlanningCycleResponse> search(DemandPlanningCycleSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            DemandPlanningCycle.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        if (roleDataScopeService.isDepartmentIssueScoped()) {
            return roleDataScopeService.page(repository.findAll(spec).stream().map(mapper::toResponse)
                .filter(roleDataScopeService::canParticipateInCycle).toList(), pageable);
        }
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public DemandPlanningCycleResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DemandPlanningCycleResponse create(DemandPlanningCycleCreateRequest dto) {
        DemandPlanningCycle entity = mapper.toEntity(dto);
        applyOwnerEmployee(entity, dto.getOwnerEmployeeId());
        applyDefaultOwnerIfMissing(entity);
        beforeCreate(entity, dto);
        DemandPlanningCycle saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        DemandPlanningCycle entity = findEntity(id);
        if (!DemandPlanningCycleStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT planning cycle can be deleted");
        }
        if (!linkedPlans(id).isEmpty()) {
            throw new BusinessException("Planning cycle with linked demand plans cannot be deleted");
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DemandPlanningCycleResponse restore(UUID id) {
        DemandPlanningCycle entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        DemandPlanningCycle saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected DemandPlanningCycle findEntity(UUID id) {
        DemandPlanningCycle entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (roleDataScopeService.isDepartmentIssueScoped()
            && !roleDataScopeService.canParticipateInCycle(mapper.toResponse(entity))) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    private void applyOwnerEmployee(DemandPlanningCycle cycle, UUID ownerEmployeeId) {
        if (ownerEmployeeId == null) {
            cycle.setOwnerEmployee(null);
            return;
        }
        Employee employee = employeeRepository.findByIdAndIsDeletedFalse(ownerEmployeeId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Employee not found: " + ownerEmployeeId));
        cycle.setOwnerEmployee(employee);
        cycle.setOwnerUsername(employee.getUser() == null ? null : employee.getUser().getUsername());
    }

    private void applyDefaultOwnerIfMissing(DemandPlanningCycle cycle) {
        if (cycle.getOwnerEmployee() != null || !blank(cycle.getOwnerUsername())) {
            return;
        }
        String username = currentUsername();
        cycle.setOwnerUsername(username);
        userRepository.resolveActiveUserByUsername(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                username
            )
            .map(user -> user.getEmployee())
            .filter(employee -> employee != null && !employee.isDeleted())
            .filter(employee -> TenantContext.currentTenant().equals(employee.getTenantId()))
            .filter(employee -> com.enterprise.mm.security.AccessScope.canAccessCompany(employee.getCompanyId()))
            .ifPresent(cycle::setOwnerEmployee);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException("Authenticated user is required to create demand planning cycle");
        }
        return authentication.getName();
    }

    protected boolean companyScoped() {
        return true;
    }
}
