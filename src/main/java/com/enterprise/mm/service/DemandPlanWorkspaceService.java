package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanItemPurchaseRequestAllocation;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.DemandPlanCoverageStatus;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanProcessStage;
import com.enterprise.mm.enums.DemandPlanStageState;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPlanWorkspaceActionKey;
import com.enterprise.mm.enums.DemandPlanWorkspaceView;
import com.enterprise.mm.enums.DepartmentSupplyStatus;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.AvailableActions;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.ApprovalStep;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.ApprovalTimeline;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanAction;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanHeader;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceDetail;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceRow;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceSummary;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.ItemSummary;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.ItemCoverage;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.ProcessStage;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.RelatedDocumentRef;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.SourceWarning;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.StatusSummaryRow;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.StatusTimelineItem;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.SummaryTotal;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.SupplySummary;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.DemandPlanApprovalAdapter;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.i18n.LocaleContextHolder;

@Service
public class DemandPlanWorkspaceService {
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository itemRepository;
    private final AuditLogRepository auditLogRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final IssueRequestLineRepository issueRequestLineRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final ServiceExecutionRepository serviceExecutionRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final DemandPlanItemPurchaseRequestAllocationRepository demandItemPrAllocationRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final UserRepository userRepository;

    public DemandPlanWorkspaceService(
        DemandPlanRepository demandPlanRepository,
        DemandPlanItemRepository itemRepository,
        AuditLogRepository auditLogRepository,
        RoleDataScopeService roleDataScopeService,
        PurchaseOrderSourceAllocationRepository sourceAllocationRepository,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        GoodsReceiptLineRepository goodsReceiptLineRepository,
        IssueRequestLineRepository issueRequestLineRepository,
        ServiceAcceptanceActRepository serviceAcceptanceActRepository,
        ServiceExecutionRepository serviceExecutionRepository,
        WorkCompletionActRepository workCompletionActRepository,
        PurchaseRequestItemRepository purchaseRequestItemRepository,
        DemandPlanItemPurchaseRequestAllocationRepository demandItemPrAllocationRepository,
        ApprovalInstanceRepository approvalInstanceRepository,
        ApprovalTaskRepository approvalTaskRepository,
        ApprovalHistoryRepository approvalHistoryRepository,
        UserRepository userRepository
    ) {
        this.demandPlanRepository = demandPlanRepository;
        this.itemRepository = itemRepository;
        this.auditLogRepository = auditLogRepository;
        this.roleDataScopeService = roleDataScopeService;
        this.sourceAllocationRepository = sourceAllocationRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.goodsReceiptLineRepository = goodsReceiptLineRepository;
        this.issueRequestLineRepository = issueRequestLineRepository;
        this.serviceAcceptanceActRepository = serviceAcceptanceActRepository;
        this.serviceExecutionRepository = serviceExecutionRepository;
        this.workCompletionActRepository = workCompletionActRepository;
        this.purchaseRequestItemRepository = purchaseRequestItemRepository;
        this.demandItemPrAllocationRepository = demandItemPrAllocationRepository;
        this.approvalInstanceRepository = approvalInstanceRepository;
        this.approvalTaskRepository = approvalTaskRepository;
        this.approvalHistoryRepository = approvalHistoryRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<DemandPlanWorkspaceRow> worklist(Filter filter, Pageable pageable) {
        Filter effective = filter == null ? Filter.empty() : filter;
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        boolean resolvedFiltering = requiresResolvedFiltering(effective);
        Pageable queryPageable = scope.hasDocumentScope() || resolvedFiltering ? Pageable.unpaged() : pageable;
        MatchingPlans matching = matchingPlans(effective, queryPageable);
        List<DemandPlan> scoped = matching.plans();
        Map<UUID, List<DemandPlanItem>> itemsByPlan = matching.itemsByPlan();
        CoverageContext coverage = matching.coverage();
        Map<String, String> ownerFullNames = userFullNamesByUsername(
            scoped.stream().map(DemandPlan::getOwnerUsername).toList());
        List<DemandPlanWorkspaceRow> rows = scoped.stream()
            .map(plan -> row(
                plan,
                itemsByPlan.getOrDefault(plan.getId(), List.of()),
                coverage,
                ownerFullNames.get(normalizeUsername(plan.getOwnerUsername()))))
            .toList();
        if (scope.hasDocumentScope() || resolvedFiltering) {
            return PageResponse.from(roleDataScopeService.page(rows, pageable));
        }
        return PageResponse.from(new PageImpl<>(rows, pageable, matching.totalElements()));
    }

    @Transactional(readOnly = true)
    public DemandPlanWorkspaceSummary summary(Filter filter) {
        MatchingPlans matching = matchingPlans(filter == null ? Filter.empty() : filter, Pageable.unpaged());
        Map<String, StatusSummaryAccumulator> byStatus = new HashMap<>();
        SummaryAccumulator total = new SummaryAccumulator();
        matching.plans().forEach(plan -> {
            String status = firstNonBlank(plan.getStatus(), "UNKNOWN");
            StatusSummaryAccumulator row = byStatus.computeIfAbsent(status, ignored -> new StatusSummaryAccumulator(status));
            row.addPlan(plan);
            total.addPlan(plan);
            matching.itemsByPlan().getOrDefault(plan.getId(), List.of())
                .forEach(item -> row.addItem(plan, item, facts(item, matching.coverage())));
        });
        List<StatusSummaryRow> rows = byStatus.values().stream()
            .sorted(Comparator.comparing(StatusSummaryAccumulator::status))
            .map(StatusSummaryAccumulator::toRow)
            .toList();
        return new DemandPlanWorkspaceSummary(rows, total.toTotal(withApprovalFlowCount(matching.plans())));
    }

    private long withApprovalFlowCount(List<DemandPlan> plans) {
        List<UUID> ids = plans.stream()
            .map(DemandPlan::getId)
            .filter(Objects::nonNull)
            .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return approvalInstanceRepository.countByTenantIdAndCompanyIdAndModuleKeyAndEntityIdInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            DemandPlanApprovalAdapter.MODULE_KEY,
            ids
        );
    }

    @Transactional(readOnly = true)
    public DemandPlanWorkspaceDetail detail(UUID id) {
        DemandPlan plan = demandPlanRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(CompanyContext.currentCompany(), candidate.getCompanyId()))
            .filter(candidate -> roleDataScopeService.canAccessDocument(
                candidate.getCreatedBy(),
                candidate.getOwnerUsername(),
                candidate.getDepartment() == null ? null : candidate.getDepartment().getId()))
            .orElseThrow(() -> new com.enterprise.mm.error.NotFoundException("DemandPlan not found: " + id));
        List<DemandPlanItem> items = itemRepository
            .findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .stream()
            .sorted(Comparator.comparing(DemandPlanItem::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        CoverageContext coverage = coverageContext(items);
        LocalDateTime generatedAt = LocalDateTime.now();
        return new DemandPlanWorkspaceDetail(
            header(plan),
            supplySummary(plan, items, coverage, generatedAt),
            stages(plan, items, coverage),
            items.stream().map(item -> itemCoverage(item, coverage)).toList(),
            approvalTimeline(plan),
            statusHistory(plan),
            relatedDocuments(plan, items, coverage),
            actions(plan, items, coverage),
            generatedAt,
            sourceWarnings(items, coverage)
        );
    }

    private MatchingPlans matchingPlans(Filter filter, Pageable pageable) {
        Page<DemandPlan> page = demandPlanRepository.findAll(spec(filter), pageable);
        List<DemandPlan> scoped = page.getContent().stream()
            .filter(plan -> roleDataScopeService.canAccessDocument(
                plan.getCreatedBy(),
                plan.getOwnerUsername(),
                plan.getDepartment() == null ? null : plan.getDepartment().getId()))
            .toList();
        Map<UUID, List<DemandPlanItem>> itemsByPlan = itemsByPlan(scoped);
        CoverageContext coverage = coverageContext(itemsByPlan.values().stream().flatMap(Collection::stream).toList());
        if (requiresResolvedFiltering(filter)) {
            scoped = scoped.stream()
                .filter(plan -> matchesResolvedFilter(filter, plan, itemsByPlan.getOrDefault(plan.getId(), List.of()), coverage))
                .toList();
        }
        return new MatchingPlans(scoped, itemsByPlan, coverage, page.getTotalElements());
    }

    private Specification<DemandPlan> spec(Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("department", JoinType.LEFT);
                root.fetch("planningCycle", JoinType.LEFT);
                query.distinct(true);
            }
            String search = blank(filter.search());
            if (search != null) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                var department = root.join("department", JoinType.LEFT);
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), like),
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(department.get("name")), like)
                ));
            }
            List<String> statuses = statuses(filter);
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (filter.planYear() != null) {
                predicates.add(cb.equal(root.get("planYear"), filter.planYear()));
            }
            if (filter.departmentId() != null) {
                predicates.add(cb.equal(root.join("department", JoinType.LEFT).get("id"), filter.departmentId()));
            }
            if (filter.planningCycleId() != null) {
                predicates.add(cb.equal(root.join("planningCycle", JoinType.LEFT).get("id"), filter.planningCycleId()));
            }
            if (blank(filter.requestType()) != null) {
                predicates.add(cb.equal(root.get("requestType"), com.enterprise.mm.enums.RequestType.valueOf(filter.requestType().trim().toUpperCase(Locale.ROOT))));
            }
            if (blank(filter.periodType()) != null) {
                predicates.add(cb.equal(root.get("periodType"), filter.periodType().trim().toUpperCase(Locale.ROOT)));
            }
            if (filter.quarter() != null) {
                predicates.add(cb.equal(root.get("quarter"), filter.quarter()));
            }
            String owner = blank(filter.ownerUsername());
            if (owner != null) {
                predicates.add(cb.equal(cb.lower(root.get("ownerUsername")), owner.toLowerCase(Locale.ROOT)));
            }
            if (filter.view() == DemandPlanWorkspaceView.MY) {
                predicates.add(cb.equal(cb.lower(root.get("ownerUsername")), currentUsername().toLowerCase(Locale.ROOT)));
            }
            if (filter.materialId() != null && query != null) {
                var itemSubquery = query.subquery(UUID.class);
                var item = itemSubquery.from(DemandPlanItem.class);
                itemSubquery.select(item.get("demandPlanId")).where(
                    cb.equal(item.get("tenantId"), TenantContext.currentTenant()),
                    cb.equal(item.get("companyId"), CompanyContext.currentCompany()),
                    cb.isFalse(item.get("isDeleted")),
                    cb.equal(item.get("materialId"), filter.materialId())
                );
                predicates.add(root.get("id").in(itemSubquery));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private List<String> statuses(Filter filter) {
        if (filter.status() != null && !filter.status().isEmpty()) {
            return filter.status().stream().filter(Objects::nonNull).map(value -> value.trim().toUpperCase(Locale.ROOT)).toList();
        }
        return switch (filter.view()) {
            case DRAFT -> List.of(DemandPlanStatus.DRAFT.name(), DemandPlanStatus.COLLECTING.name());
            case ON_APPROVAL -> List.of(DemandPlanStatus.SUBMITTED.name());
            case IN_PROCUREMENT -> List.of(DemandPlanStatus.APPROVED.name(), DemandPlanStatus.ACTIVE.name());
            case SUPPLIED, PARTIALLY_SUPPLIED -> List.of();
            case ALL, MY -> List.of();
        };
    }

    private boolean requiresResolvedFiltering(Filter filter) {
        return filter.processStage() != null
            || filter.view() == DemandPlanWorkspaceView.SUPPLIED
            || filter.view() == DemandPlanWorkspaceView.PARTIALLY_SUPPLIED;
    }

    private boolean matchesResolvedFilter(
        Filter filter,
        DemandPlan plan,
        List<DemandPlanItem> items,
        CoverageContext coverage
    ) {
        if (filter.processStage() != null && currentStage(plan, items, coverage) != filter.processStage()) {
            return false;
        }
        if (filter.view() == DemandPlanWorkspaceView.SUPPLIED) {
            return supplyStatus(plan, items, coverage) == DepartmentSupplyStatus.SUPPLIED;
        }
        if (filter.view() == DemandPlanWorkspaceView.PARTIALLY_SUPPLIED) {
            return supplyStatus(plan, items, coverage) == DepartmentSupplyStatus.PARTIAL;
        }
        return true;
    }

    private Map<UUID, List<DemandPlanItem>> itemsByPlan(List<DemandPlan> plans) {
        List<UUID> ids = plans.stream().map(DemandPlan::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findByTenantIdAndDemandPlan_IdInAndIsDeletedFalse(TenantContext.currentTenant(), ids)
            .stream()
            .collect(Collectors.groupingBy(DemandPlanItem::getDemandPlanId));
    }

    private DemandPlanWorkspaceRow row(
        DemandPlan plan,
        List<DemandPlanItem> items,
        CoverageContext coverage,
        String ownerFullName
    ) {
        Counts counts = counts(items, coverage);
        return new DemandPlanWorkspaceRow(
            plan.getId(),
            plan.getCode(),
            plan.getName(),
            plan.getRequestType() == null ? null : plan.getRequestType().name(),
            plan.getStatus(),
            plan.getDepartment() == null ? null : plan.getDepartment().getId(),
            plan.getDepartment() == null ? null : plan.getDepartment().getName(),
            plan.getPlanYear(),
            plan.getPeriodType(),
            plan.getQuarter(),
            plan.getPeriodStart(),
            plan.getPeriodEnd(),
            plan.getPlanningCycle() == null ? null : plan.getPlanningCycle().getId(),
            plan.getPlanningCycle() == null ? null : plan.getPlanningCycle().getName(),
            plan.getOwnerUsername(),
            ownerFullName,
            plan.getCurrencyCode(),
            nvl(plan.getTotalPlannedAmount()),
            nvl(plan.getTotalApprovedAmount()),
            currentStage(plan, items, coverage).name(),
            currentState(plan).name(),
            supplyStatus(plan, items, coverage).name(),
            counts.total(),
            counts.ordered(),
            counts.partial(),
            counts.full(),
            plan.getCreatedAt(),
            plan.getUpdatedAt(),
            primaryAction(plan, items, coverage)
        );
    }

    private DemandPlanHeader header(DemandPlan plan) {
        return new DemandPlanHeader(
            plan.getId(),
            plan.getCode(),
            plan.getName(),
            plan.getRequestType() == null ? null : plan.getRequestType().name(),
            plan.getStatus(),
            plan.getDepartment() == null ? null : plan.getDepartment().getId(),
            plan.getDepartment() == null ? null : plan.getDepartment().getName(),
            plan.getPlanYear(),
            plan.getPeriodType(),
            plan.getQuarter(),
            plan.getPeriodStart(),
            plan.getPeriodEnd(),
            plan.getPlanningCycle() == null ? null : plan.getPlanningCycle().getId(),
            plan.getPlanningCycle() == null ? null : plan.getPlanningCycle().getName(),
            plan.getOwnerUsername(),
            ownerFullName(plan.getOwnerUsername()),
            plan.getCurrencyCode(),
            nvl(plan.getTotalPlannedAmount()),
            nvl(plan.getTotalApprovedAmount()),
            plan.getCreatedAt(),
            plan.getVersion()
        );
    }

    private SupplySummary supplySummary(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage, LocalDateTime generatedAt) {
        Counts counts = counts(items, coverage);
        return new SupplySummary(
            supplyStatus(plan, items, coverage).name(),
            counts.total(),
            counts.ordered(),
            counts.partial(),
            counts.full(),
            generatedAt,
            supplierNames(items, coverage),
            issueActors(items, coverage)
        );
    }

    private List<ProcessStage> stages(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage) {
        DemandPlanProcessStage current = currentStage(plan, items, coverage);
        boolean approvalCompleted = planApprovalCompleted(plan);
        if (approvalCompleted && current == DemandPlanProcessStage.PLAN_APPROVAL) {
            current = DemandPlanProcessStage.PURCHASE_REQUEST;
        }
        String planOwner = firstNonBlank(ownerFullName(plan.getOwnerUsername()), plan.getOwnerUsername());
        List<ProcessStage> result = new ArrayList<>();
        for (DemandPlanProcessStage stage : workspaceStages()) {
            DemandPlanStageState state = stageState(stage, current, plan, approvalCompleted, items, coverage);
            if (stage == DemandPlanProcessStage.DEPARTMENT_SUPPLY && supplyStatus(plan, items, coverage) == DepartmentSupplyStatus.UNAVAILABLE) {
                state = DemandPlanStageState.UNSTARTED;
            }
            String responsible = stage == DemandPlanProcessStage.DEMAND_PLAN ? planOwner : null;
            result.add(new ProcessStage(stage.name(), state.name(), stage == current, null, null, responsible, null, stageSummary(stage, items, coverage), documentsForStage(stage, items, coverage)));
        }
        return result;
    }

    private DemandPlanStageState stageState(
        DemandPlanProcessStage stage,
        DemandPlanProcessStage current,
        DemandPlan plan,
        boolean approvalCompleted,
        List<DemandPlanItem> items,
        CoverageContext coverage
    ) {
        if (stage == DemandPlanProcessStage.PLAN_APPROVAL) {
            if (approvalCompleted) {
                return DemandPlanStageState.COMPLETED;
            }
            if (DemandPlanStatus.SUBMITTED.name().equals(plan.getStatus())) {
                return DemandPlanStageState.IN_PROGRESS;
            }
        }
        if (stage == DemandPlanProcessStage.PURCHASE_ORDER && hasPurchaseOrder(items, coverage)) {
            return DemandPlanStageState.COMPLETED;
        }
        if (stage == DemandPlanProcessStage.INBOUND_OR_EXECUTION) {
            DemandPlanStageState serviceWorkState = serviceWorkExecutionState(items, coverage);
            if (serviceWorkState != null) {
                return serviceWorkState;
            }
        }
        if (stage == DemandPlanProcessStage.RECEIPT_OR_ACCEPTANCE) {
            DemandPlanStageState serviceWorkState = serviceWorkAcceptanceState(items, coverage);
            if (serviceWorkState != null) {
                return serviceWorkState;
            }
        }
        return stage.ordinal() < current.ordinal()
            ? DemandPlanStageState.COMPLETED
            : stage == current ? currentState(plan) : DemandPlanStageState.NOT_STARTED;
    }

    private boolean hasPurchaseOrder(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .map(item -> facts(item, coverage))
            .anyMatch(fact -> !fact.purchaseOrderItemIds().isEmpty());
    }

    private DemandPlanStageState serviceWorkExecutionState(List<DemandPlanItem> items, CoverageContext coverage) {
        List<ItemFacts> facts = items.stream().map(item -> facts(item, coverage)).toList();
        if (facts.stream().noneMatch(ItemFacts::serviceOrWorkLine)) {
            return null;
        }
        if (facts.stream().anyMatch(fact -> fact.serviceAcceptanceActExists()
            || fact.workCompletionActExists()
            || fact.acceptedServiceAcceptanceActExists()
            || fact.acceptedWorkCompletionActExists())) {
            return DemandPlanStageState.COMPLETED;
        }
        if (facts.stream().anyMatch(ItemFacts::serviceExecutionExists)) {
            if (facts.stream().anyMatch(ItemFacts::activeServiceExecutionExists)) {
                return DemandPlanStageState.IN_PROGRESS;
            }
            if (facts.stream().anyMatch(ItemFacts::completedServiceExecutionExists)) {
                return DemandPlanStageState.COMPLETED;
            }
            return DemandPlanStageState.NOT_STARTED;
        }
        return hasPurchaseOrder(items, coverage) ? DemandPlanStageState.NOT_STARTED : null;
    }

    private DemandPlanStageState serviceWorkAcceptanceState(List<DemandPlanItem> items, CoverageContext coverage) {
        List<ItemFacts> facts = items.stream().map(item -> facts(item, coverage)).toList();
        if (facts.stream().noneMatch(ItemFacts::serviceOrWorkLine)) {
            return null;
        }
        if (facts.stream().anyMatch(fact -> fact.acceptedServiceAcceptanceActExists() || fact.acceptedWorkCompletionActExists())) {
            return DemandPlanStageState.COMPLETED;
        }
        if (facts.stream().anyMatch(fact -> fact.serviceAcceptanceActExists() || fact.workCompletionActExists())) {
            return DemandPlanStageState.IN_PROGRESS;
        }
        return DemandPlanStageState.NOT_STARTED;
    }

    private boolean planApprovalCompleted(DemandPlan plan) {
        if (plan == null) {
            return false;
        }
        if (List.of(
            DemandPlanStatus.APPROVED.name(),
            DemandPlanStatus.ACTIVE.name(),
            DemandPlanStatus.CLOSED.name()
        ).contains(plan.getStatus())) {
            return true;
        }
        return approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                DemandPlanApprovalAdapter.MODULE_KEY,
                plan.getId()
            )
            .map(instance -> ApprovalInstanceStatus.APPROVED.equals(instance.getApprovalStatus())
                || ApprovalInstanceStatus.APPROVED.name().equals(instance.getStatus()))
            .orElse(false);
    }

    private ItemCoverage itemCoverage(DemandPlanItem item, CoverageContext coverage) {
        ItemFacts facts = facts(item, coverage);
        return new ItemCoverage(
            item.getId(),
            item.getCode(),
            item.getName(),
            item.getMaterialId(),
            item.getMaterial() == null ? null : item.getMaterial().getName(),
            localizedUnitName(item),
            nvl(item.getRequestedQuantity()),
            visibleApprovedQuantity(item),
            nvl(item.getReservedQuantity()),
            facts.ordered(),
            facts.receivedOrAccepted(),
            facts.departmentIssued(),
            coverageStatus(item, facts).name(),
            item.getPurchaseRequestItemId(),
            item.getRequiredDate(),
            item.getNeedByDate(),
            item.getPriority(),
            supplierNames(item, coverage),
            relatedDocuments(item, coverage),
            nvl(item.getStockFulfillmentQuantity()),
            nvl(item.getStockFulfillmentQuantity()).subtract(facts.departmentIssued()).max(BigDecimal.ZERO),
            nvl(item.getStockFulfillmentQuantity()).add(nvl(item.getApprovedQuantity())),
            stockAvailableToRequest(item, coverage)
        ).withUnitShortName(com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(item.getMaterial() == null ? null : item.getMaterial().getUnitOfMeasure(), LocaleContextHolder.getLocale()));
    }

    private BigDecimal stockAvailableToRequest(DemandPlanItem item, CoverageContext coverage) {
        if (item.getDemandPlan() == null || !List.of("APPROVED", "ACTIVE").contains(item.getDemandPlan().getStatus())) return BigDecimal.ZERO;
        BigDecimal claimed = coverage.issueLinesByDemandPlanItem().getOrDefault(item.getId(), List.of()).stream()
            .map(line -> {
                String status = line.getIssueRequest().getStatus();
                if (List.of("CANCELLED", "REJECTED").contains(status)) return BigDecimal.ZERO;
                return "CLOSED".equals(status) ? nvl(line.getIssuedQuantity()) : nvl(line.getRequestedQuantity());
            }).reduce(BigDecimal.ZERO, BigDecimal::add);
        return nvl(item.getStockFulfillmentQuantity()).subtract(claimed).max(BigDecimal.ZERO);
    }

    private String localizedUnitName(DemandPlanItem item) {
        Material material = item == null ? null : item.getMaterial();
        UnitOfMeasure unit = material == null ? null : material.getUnitOfMeasure();
        if (unit == null) {
            return null;
        }
        String language = LocaleContextHolder.getLocale().getLanguage();
        String localizedLong = switch (language) {
            case "uz" -> unit.getMeasurementNameLongUz();
            case "ru" -> unit.getMeasurementNameLongRu();
            default -> unit.getMeasurementNameLong();
        };
        return firstNonBlank(
            localizedLong,
            unit.getMeasurementNameLong(),
            unit.getName(),
            unit.getMeasurementNameShort(),
            unit.getCode()
        );
    }

    private BigDecimal visibleApprovedQuantity(DemandPlanItem item) {
        String status = item == null ? null : item.getStatus();
        if (DemandPlanItemStatus.APPROVED.name().equals(status)
            || DemandPlanItemStatus.RESERVED.name().equals(status)
            || DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(status)) {
            return nvl(item.getApprovedQuantity());
        }
        return BigDecimal.ZERO;
    }

    private ApprovalTimeline approvalTimeline(DemandPlan plan) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                DemandPlanApprovalAdapter.MODULE_KEY,
                plan.getId()
            )
            .orElse(null);
        if (instance == null) {
            return new ApprovalTimeline(DemandPlanApprovalAdapter.MODULE_KEY, null, "NOT_STARTED", List.of());
        }
        List<ApprovalTask> tasks = approvalTaskRepository
            .findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), instance.getId());
        List<ApprovalHistory> histories = approvalHistoryRepository
            .findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(
                TenantContext.currentTenant(), instance.getId());
        Map<Integer, ApprovalHistory> latestHistoryByStep = histories
            .stream()
            .filter(history -> history.getFromStepOrder() != null || history.getToStepOrder() != null)
            .collect(Collectors.toMap(
                history -> history.getFromStepOrder() == null ? history.getToStepOrder() : history.getFromStepOrder(),
                history -> history,
                (first, second) -> second
            ));
        Map<UUID, String> userNamesById = approvalUserNamesById(tasks);
        Map<String, String> userNamesByUsername = approvalUserNamesByUsername(tasks, histories);
        List<ApprovalStep> steps = tasks.stream()
            .map(task -> approvalStep(task, latestHistoryByStep.get(task.getStepOrder()), userNamesById, userNamesByUsername))
            .toList();
        return new ApprovalTimeline(
            instance.getModuleKey(),
            instance.getId(),
            instance.getApprovalStatus() == null ? instance.getStatus() : instance.getApprovalStatus().name(),
            steps
        );
    }

    private ApprovalStep approvalStep(
        ApprovalTask task,
        ApprovalHistory history,
        Map<UUID, String> userNamesById,
        Map<String, String> userNamesByUsername
    ) {
        String assigneeUsername = blank(task.getAssigneeUsername());
        String assignee = firstNonBlank(
            task.getAssigneeUserId() == null ? null : userNamesById.get(task.getAssigneeUserId()),
            assigneeUsername == null ? null : userNamesByUsername.get(normalizeUsername(assigneeUsername)),
            task.getAssigneeRole()
        );
        String actingUserRaw = firstNonBlank(task.getActedBy(), history == null ? null : history.getActor());
        String actingUser = firstNonBlank(
            actingUserRaw == null ? null : userNamesByUsername.get(normalizeUsername(actingUserRaw)),
            actingUserRaw
        );
        LocalDateTime actionTime = task.getActedAt() == null && history != null ? history.getActionAt() : task.getActedAt();
        String comment = firstNonBlank(task.getCommentText(), history == null ? null : history.getCommentText());
        return new ApprovalStep(
            task.getStepOrder(),
            firstNonBlank(task.getName(), "Step " + task.getStepOrder()),
            assignee,
            task.getTaskStatus() == null ? task.getStatus() : task.getTaskStatus().name(),
            actingUser,
            actionTime,
            comment
        );
    }

    private Map<UUID, String> approvalUserNamesById(List<ApprovalTask> tasks) {
        Set<UUID> userIds = tasks.stream()
            .map(ApprovalTask::getAssigneeUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return userNamesById(userIds);
    }

    private Map<UUID, String> userNamesById(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds)
            .stream()
            .filter(user -> TenantContext.currentTenant().equals(user.getTenantId()))
            .filter(user -> user.getCompanyId() == null || Objects.equals(CompanyContext.currentCompany(), user.getCompanyId()))
            .filter(user -> !user.isDeleted())
            .collect(Collectors.toMap(User::getId, this::userDisplayName, (first, second) -> first));
    }

    private Map<String, String> approvalUserNamesByUsername(List<ApprovalTask> tasks, List<ApprovalHistory> history) {
        Set<String> usernames = new HashSet<>();
        tasks.stream().map(ApprovalTask::getAssigneeUsername).map(this::blank).filter(Objects::nonNull).forEach(usernames::add);
        tasks.stream().map(ApprovalTask::getActedBy).map(this::blank).filter(Objects::nonNull).forEach(usernames::add);
        history.stream().map(ApprovalHistory::getActor).map(this::blank).filter(Objects::nonNull).forEach(usernames::add);
        if (usernames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        usernames.forEach(username -> userRepository
            .resolveActiveUserByUsername(TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
            .ifPresent(user -> names.put(normalizeUsername(username), userDisplayName(user))));
        return names;
    }

    private List<StatusTimelineItem> statusHistory(DemandPlan plan) {
        List<AuditLog> logs = auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "DemandPlan", plan.getId());
        Map<String, String> userNamesByUsername = userFullNamesByUsername(logs.stream()
            .filter(log -> blank(log.getFullName()) == null)
            .map(AuditLog::getUsername).toList());
        return logs.stream()
            .map(log -> statusItem(log, userNamesByUsername))
            .toList();
    }

    private StatusTimelineItem statusItem(AuditLog log, Map<String, String> namesByUsername) {
        // Preserve the recorded name. Older logs have only a login; resolve it
        // within the current scope, retaining that login if no name is available.
        String actor = firstNonBlank(blank(log.getFullName()),
            namesByUsername.get(normalizeUsername(log.getUsername())), blank(log.getUsername()));
        return new StatusTimelineItem(log.getId(), log.getAction(), actor, log.getDetails(), log.getOccurredAt());
    }

    private AvailableActions actions(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage) {
        DemandPlanAction primary = primaryAction(plan, items, coverage);
        List<DemandPlanAction> secondary = new ArrayList<>();
        List<DemandPlanAction> overflow = new ArrayList<>();
        String status = plan.getStatus();
        if (DemandPlanStatus.DRAFT.name().equals(status) || DemandPlanStatus.COLLECTING.name().equals(status)) {
            secondary.add(commandAction(DemandPlanWorkspaceActionKey.EDIT, canOriginateDemandPlan(), false, canOriginateDemandPlan() ? null : "demand_plan.action.forbidden"));
        }
        if (DemandPlanStatus.APPROVED.name().equals(status)) {
            secondary.add(commandAction(DemandPlanWorkspaceActionKey.CREATE_PURCHASE_REQUEST, canMutateDemandPlan() && hasItemsReadyForPurchaseRequest(items), false,
                !canMutateDemandPlan() ? "demand_plan.action.forbidden" : hasItemsReadyForPurchaseRequest(items) ? null : "demand_plan.action.no_items_ready_for_purchase_request"));
        }
        if (DemandPlanStatus.ACTIVE.name().equals(status) && !hasItemsReadyForPurchaseRequest(items)) {
            DemandPlanAction open = openPurchaseOrderAction(items, coverage);
            if (open == null) {
                open = openPurchaseRequestAction(items, coverage);
            }
            if (open != null) {
                secondary.add(open);
            }
        }
        if (DemandPlanStatus.APPROVED.name().equals(status) || DemandPlanStatus.ACTIVE.name().equals(status)) {
            overflow.add(commandAction(DemandPlanWorkspaceActionKey.CLOSE, canMutateDemandPlan(), false, canMutateDemandPlan() ? null : "demand_plan.action.forbidden"));
        }
        if (DemandPlanStatus.DRAFT.name().equals(status)) {
            overflow.add(commandAction(DemandPlanWorkspaceActionKey.DELETE, canOriginateDemandPlan(), true, canOriginateDemandPlan() ? null : "demand_plan.action.forbidden"));
        }
        if (!List.of(DemandPlanStatus.CLOSED.name(), DemandPlanStatus.CANCELLED.name()).contains(status)) {
            overflow.add(commandAction(DemandPlanWorkspaceActionKey.CANCEL, canMutateDemandPlan(), true, canMutateDemandPlan() ? null : "demand_plan.action.forbidden"));
        }
        return new AvailableActions(primary, secondary, overflow);
    }

    private DemandPlanAction primaryAction(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage) {
        String status = plan.getStatus();
        if (DemandPlanStatus.DRAFT.name().equals(status)) {
            return commandAction(DemandPlanWorkspaceActionKey.START_COLLECTION, canOriginateDemandPlan(), false,
                canOriginateDemandPlan() ? null : "demand_plan.action.forbidden");
        }
        if (DemandPlanStatus.COLLECTING.name().equals(status)) {
            boolean ready = hasSubmittableItems(items);
            return commandAction(DemandPlanWorkspaceActionKey.SUBMIT, canOriginateDemandPlan() && ready, false,
                !canOriginateDemandPlan() ? "demand_plan.action.forbidden" : ready ? null : "demand_plan.action.no_items");
        }
        if (DemandPlanStatus.SUBMITTED.name().equals(status)) {
            return approvalNavigationAction(plan);
        }
        if (DemandPlanStatus.APPROVED.name().equals(status)) {
            return commandAction(DemandPlanWorkspaceActionKey.ACTIVATE, canMutateDemandPlan(), false,
                canMutateDemandPlan() ? null : "demand_plan.action.forbidden");
        }
        if (DemandPlanStatus.ACTIVE.name().equals(status)) {
            if (hasItemsReadyForPurchaseRequest(items)) {
                return commandAction(DemandPlanWorkspaceActionKey.CREATE_PURCHASE_REQUEST, canMutateDemandPlan(), false,
                    canMutateDemandPlan() ? null : "demand_plan.action.forbidden");
            }
            DemandPlanAction open = openPurchaseOrderAction(items, coverage);
            if (open == null) {
                open = openPurchaseRequestAction(items, coverage);
            }
            return open == null
                ? commandAction(DemandPlanWorkspaceActionKey.CREATE_PURCHASE_REQUEST, false, false, "demand_plan.action.no_items_ready_for_purchase_request")
                : open;
        }
        return null;
    }

    private DemandPlanAction commandAction(DemandPlanWorkspaceActionKey key, boolean enabled, boolean confirmationRequired, String disabledReasonCode) {
        return new DemandPlanAction(key.name(), enabled, disabledReasonCode, Map.of(), confirmationRequired, "COMMAND", null);
    }

    private DemandPlanAction approvalNavigationAction(DemandPlan plan) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), DemandPlanApprovalAdapter.MODULE_KEY, plan.getId())
            .orElse(null);
        RelatedDocumentRef target = instance == null
            ? null
            : new RelatedDocumentRef("APPROVAL_INSTANCE", instance.getId(), "approval-tasks", instance.getCode(), instance.getName(), instance.getStatus());
        return new DemandPlanAction(DemandPlanWorkspaceActionKey.TRACK_APPROVAL.name(), instance != null, instance == null ? "demand_plan.action.approval_not_started" : null, Map.of(), false, "NAVIGATION", target);
    }

    private DemandPlanAction openPurchaseRequestAction(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .map(DemandPlanItem::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .map(coverage.purchaseRequestItems()::get)
            .filter(Objects::nonNull)
            .map(PurchaseRequestItem::getPurchaseRequest)
            .filter(Objects::nonNull)
            .findFirst()
            .map(request -> new DemandPlanAction(
                DemandPlanWorkspaceActionKey.OPEN_PURCHASE_REQUEST.name(),
                true,
                null,
                Map.of(),
                false,
                "NAVIGATION",
                new RelatedDocumentRef("PURCHASE_REQUEST", request.getId(), "purchase-requests", request.getCode(), request.getName(), request.getStatus())
            ))
            .orElse(null);
    }

    private DemandPlanAction openPurchaseOrderAction(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .flatMap(item -> facts(item, coverage).purchaseOrderItemIds().stream())
            .map(coverage.purchaseOrderItems()::get)
            .filter(Objects::nonNull)
            .map(PurchaseOrderItem::getPurchaseOrder)
            .filter(Objects::nonNull)
            .findFirst()
            .map(order -> new DemandPlanAction(
                DemandPlanWorkspaceActionKey.OPEN_PURCHASE_ORDER.name(),
                true,
                null,
                Map.of(),
                false,
                "NAVIGATION",
                new RelatedDocumentRef("PURCHASE_ORDER", order.getId(), "purchase-orders", order.getCode(), order.getName(), order.getStatus())
            ))
            .orElse(null);
    }

    private boolean hasSubmittableItems(List<DemandPlanItem> items) {
        return items.stream().anyMatch(item -> nvl(item.getRequestedQuantity()).signum() > 0);
    }

    private boolean hasItemsReadyForPurchaseRequest(List<DemandPlanItem> items) {
        return items.stream().anyMatch(this::isItemReadyForPurchaseRequest);
    }

    private boolean isItemReadyForPurchaseRequest(DemandPlanItem item) {
        return item.getPurchaseRequestItemId() == null
            && List.of(DemandPlanItemStatus.APPROVED.name(), DemandPlanItemStatus.RESERVED.name()).contains(item.getStatus())
            && nvl(item.getApprovedQuantity()).signum() > 0;
    }

    private boolean canMutateDemandPlan() {
        Set<String> roles = currentRoles();
        return roles.stream().anyMatch(role -> List.of(
            "ADMIN",
            "MM_MANAGER",
            "PROCUREMENT_MANAGER",
            "PROCUREMENT_SPECIALIST",
            "DEPARTMENT_HEAD"
        ).contains(role));
    }

    private boolean canOriginateDemandPlan() {
        Set<String> roles = currentRoles();
        return roles.stream().anyMatch(role -> List.of(
            "ADMIN",
            "MM_MANAGER",
            "PROCUREMENT_MANAGER",
            "PROCUREMENT_SPECIALIST",
            "DEPARTMENT_HEAD",
            "DEPARTMENT_WORKER"
        ).contains(role));
    }

    private DemandPlanProcessStage currentStage(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage) {
        String status = plan.getStatus();
        if (DemandPlanStatus.SUBMITTED.name().equals(status)) {
            return DemandPlanProcessStage.PLAN_APPROVAL;
        }
        if (DemandPlanStatus.APPROVED.name().equals(status)) {
            return DemandPlanProcessStage.PURCHASE_REQUEST;
        }
        List<ItemFacts> facts = items.stream().map(item -> facts(item, coverage)).toList();
        if (!facts.isEmpty() && facts.stream().allMatch(ItemFacts::supplied)) {
            return DemandPlanProcessStage.DEPARTMENT_SUPPLY;
        }
        if (facts.stream().anyMatch(fact -> fact.departmentIssued().signum() > 0)) {
            return DemandPlanProcessStage.DEPARTMENT_SUPPLY;
        }
        if (facts.stream().anyMatch(fact -> fact.serviceAcceptanceActExists()
            || fact.workCompletionActExists()
            || fact.acceptedServiceAcceptanceActExists()
            || fact.acceptedWorkCompletionActExists())) {
            return DemandPlanProcessStage.RECEIPT_OR_ACCEPTANCE;
        }
        if (facts.stream().anyMatch(fact -> fact.receivedOrAccepted().signum() > 0)) {
            return DemandPlanProcessStage.RECEIPT_OR_ACCEPTANCE;
        }
        if (facts.stream().anyMatch(ItemFacts::serviceExecutionExists)) {
            return DemandPlanProcessStage.INBOUND_OR_EXECUTION;
        }
        if (facts.stream().anyMatch(fact -> !fact.purchaseOrderItemIds().isEmpty())) {
            return DemandPlanProcessStage.INBOUND_OR_EXECUTION;
        }
        List<PurchaseRequest> purchaseRequests = linkedPurchaseRequests(items, coverage);
        if (!purchaseRequests.isEmpty()) {
            return purchaseRequests.stream().allMatch(this::purchaseRequestReadyForSourcing)
                ? DemandPlanProcessStage.SOURCING
                : DemandPlanProcessStage.PURCHASE_REQUEST;
        }
        if (DemandPlanStatus.ACTIVE.name().equals(status)) {
            return DemandPlanProcessStage.PURCHASE_REQUEST;
        }
        return DemandPlanProcessStage.DEMAND_PLAN;
    }

    private List<PurchaseRequest> linkedPurchaseRequests(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .map(DemandPlanItem::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .map(coverage.purchaseRequestItems()::get)
            .filter(Objects::nonNull)
            .map(PurchaseRequestItem::getPurchaseRequest)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(PurchaseRequest::getId, request -> request, (first, second) -> first))
            .values()
            .stream()
            .toList();
    }

    private boolean purchaseRequestReadyForSourcing(PurchaseRequest request) {
        return List.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
        ).contains(request.getStatus());
    }

    private DemandPlanStageState currentState(DemandPlan plan) {
        if (DemandPlanStatus.CANCELLED.name().equals(plan.getStatus())) {
            return DemandPlanStageState.CANCELLED;
        }
        if (DemandPlanStatus.CLOSED.name().equals(plan.getStatus())) {
            return DemandPlanStageState.COMPLETED;
        }
        return DemandPlanStageState.IN_PROGRESS;
    }

    private DepartmentSupplyStatus supplyStatus(DemandPlan plan, List<DemandPlanItem> items, CoverageContext coverage) {
        if (plan != null) {
            String status = plan.getStatus();
            if (DemandPlanStatus.DRAFT.name().equals(status) || DemandPlanStatus.COLLECTING.name().equals(status)) {
                return DepartmentSupplyStatus.NOT_STARTED;
            }
        }
        if (items.isEmpty()) {
            return DepartmentSupplyStatus.NOT_STARTED;
        }
        List<ItemFacts> facts = items.stream().map(item -> facts(item, coverage)).toList();
        if (facts.stream().allMatch(ItemFacts::supplied)) {
            return DepartmentSupplyStatus.SUPPLIED;
        }
        if (facts.stream().anyMatch(fact -> fact.departmentIssued().signum() > 0 || fact.receivedOrAccepted().signum() > 0)) {
            return DepartmentSupplyStatus.PARTIAL;
        }
        if (facts.stream().anyMatch(ItemFacts::missingRequiredLineage)) {
            return DepartmentSupplyStatus.UNAVAILABLE;
        }
        return DepartmentSupplyStatus.NOT_STARTED;
    }

    private List<SourceWarning> sourceWarnings(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .filter(item -> facts(item, coverage).missingRequiredLineage())
            .map(item -> new SourceWarning(
                "demand_plan.workspace.missing_purchase_order_source_allocation",
                "Demand item has purchase request lineage, but no purchase order source allocation was found.",
                item.getId()
            ))
            .toList();
    }

    private Counts counts(List<DemandPlanItem> items, CoverageContext coverage) {
        int ordered = 0;
        int partial = 0;
        int full = 0;
        for (DemandPlanItem item : items) {
            ItemFacts facts = facts(item, coverage);
            DemandPlanCoverageStatus status = coverageStatus(item, facts);
            if (facts.ordered().signum() > 0) {
                ordered++;
            }
            if (status == DemandPlanCoverageStatus.PARTIAL) {
                partial++;
            }
            if (status == DemandPlanCoverageStatus.SUPPLIED) {
                full++;
            }
        }
        return new Counts(items.size(), ordered, partial, full);
    }

    private CoverageContext coverageContext(List<DemandPlanItem> items) {
        List<UUID> demandItemIds = items.stream().map(DemandPlanItem::getId).filter(Objects::nonNull).distinct().toList();
        List<DemandPlanItemPurchaseRequestAllocation> persistedDemandAllocations = demandItemIds.isEmpty()
            ? List.of()
            : demandItemPrAllocationRepository
                .findByTenantIdAndCompanyIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), demandItemIds);
        List<DemandPrAllocation> demandAllocations = new ArrayList<>();
        persistedDemandAllocations.stream()
            .map(allocation -> new DemandPrAllocation(
                allocation.getDemandPlanItemId(),
                allocation.getPurchaseRequestItemId(),
                allocation.getAllocatedQuantity(),
                allocation.getAllocatedAmount()))
            .forEach(demandAllocations::add);
        Set<UUID> demandItemsWithPersistedAllocation = persistedDemandAllocations.stream()
            .map(DemandPlanItemPurchaseRequestAllocation::getDemandPlanItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        items.stream()
            .filter(item -> item.getId() != null)
            .filter(item -> item.getPurchaseRequestItemId() != null)
            .filter(item -> !demandItemsWithPersistedAllocation.contains(item.getId()))
            .map(item -> new DemandPrAllocation(
                item.getId(),
                item.getPurchaseRequestItemId(),
                nvl(item.getApprovedQuantity()),
                nvl(item.getEstimatedAmount())))
            .forEach(demandAllocations::add);
        Map<UUID, List<DemandPrAllocation>> demandAllocationsByDemandItem = demandAllocations.stream()
            .collect(Collectors.groupingBy(DemandPrAllocation::demandPlanItemId));
        Map<UUID, BigDecimal> demandAllocationTotalsByPurchaseRequestItem = demandAllocations.stream()
            .collect(Collectors.groupingBy(
                DemandPrAllocation::purchaseRequestItemId,
                Collectors.mapping(allocation -> nvl(allocation.allocatedQuantity()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        List<UUID> purchaseRequestItemIds = demandAllocations.stream()
            .map(DemandPrAllocation::purchaseRequestItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, PurchaseRequestItem> purchaseRequestItems = purchaseRequestItemIds.isEmpty()
            ? Map.of()
            : purchaseRequestItemRepository.findWorkspaceLineageItems(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds)
                .stream()
                .collect(Collectors.toMap(PurchaseRequestItem::getId, item -> item));
        List<PurchaseOrderSourceAllocation> allocations = purchaseRequestItemIds.isEmpty()
            ? List.of()
            : sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds);
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPrItem = allocations.stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseRequestItemId));
        Set<UUID> purchaseOrderItemIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseOrderItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, PurchaseOrderItem> purchaseOrderItems = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : purchaseOrderItemRepository.findWorkspaceLineageItems(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        Map<UUID, List<GoodsReceiptLine>> receiptsByPoItem = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.groupingBy(GoodsReceiptLine::getPurchaseOrderItemId));
        Map<UUID, List<IssueRequestLine>> issueLinesByDemandItem = demandItemIds.isEmpty()
            ? Map.of()
            : issueRequestLineRepository.findWorkspaceDemandSourceLines(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), demandItemIds)
                .stream()
                .collect(Collectors.groupingBy(IssueRequestLine::getSourceDemandPlanItemId));
        List<UUID> purchaseOrderIds = purchaseOrderItems.values().stream()
            .map(PurchaseOrderItem::getPurchaseOrderId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        List<UUID> serviceOrderIds = purchaseOrderItems.values().stream()
            .map(PurchaseOrderItem::getServiceOrderId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, List<ServiceExecution>> serviceExecutionsByServiceOrder = serviceOrderIds.isEmpty()
            ? Map.of()
            : serviceExecutionRepository.findByTenantIdAndCompanyIdAndServiceOrderIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), serviceOrderIds)
                .stream()
                .collect(Collectors.groupingBy(ServiceExecution::getServiceOrderId));
        List<String> serviceStatuses = serviceActStatuses();
        Map<UUID, List<ServiceAcceptanceAct>> serviceActsByPo = purchaseOrderIds.isEmpty()
            ? Map.of()
            : serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderIds, serviceStatuses)
                .stream()
                .collect(Collectors.groupingBy(ServiceAcceptanceAct::getPurchaseOrderId));
        List<String> workStatuses = workActStatuses();
        Map<UUID, List<WorkCompletionAct>> workActsByPoItem = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : workCompletionActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds), workStatuses)
                .stream()
                .collect(Collectors.groupingBy(WorkCompletionAct::getPurchaseOrderItemId));
        Map<UUID, BigDecimal> allocatedTotalsByPoItem = allocations.stream()
            .collect(Collectors.groupingBy(
                PurchaseOrderSourceAllocation::getPurchaseOrderItemId,
                Collectors.mapping(allocation -> nvl(allocation.getOrderedQuantity()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
            ));
        return new CoverageContext(
            demandAllocationsByDemandItem,
            demandAllocationTotalsByPurchaseRequestItem,
            allocationsByPrItem,
            purchaseOrderItems,
            receiptsByPoItem,
            issueLinesByDemandItem,
            serviceActsByPo,
            serviceExecutionsByServiceOrder,
            workActsByPoItem,
            allocatedTotalsByPoItem,
            purchaseRequestItems
        );
    }

    private ItemFacts facts(DemandPlanItem item, CoverageContext coverage) {
        List<DemandPrAllocation> demandAllocations = coverage.demandAllocationsByDemandItem()
            .getOrDefault(item.getId(), List.of());
        BigDecimal ordered = BigDecimal.ZERO;
        BigDecimal receivedOrAccepted = BigDecimal.ZERO;
        // Existing-stock fulfillment does not require a purchase order to identify a material line.
        boolean materialLine = item.getMaterialTypeSnapshot() == com.enterprise.mm.enums.MaterialType.TMB;
        boolean serviceOrWorkLine = false;
        boolean serviceExecutionExists = false;
        boolean activeServiceExecutionExists = false;
        boolean completedServiceExecutionExists = false;
        boolean serviceAcceptanceActExists = false;
        boolean acceptedServiceAcceptanceActExists = false;
        boolean workCompletionActExists = false;
        boolean acceptedWorkCompletionActExists = false;
        Set<UUID> poItemIds = new HashSet<>();
        for (DemandPrAllocation demandAllocation : demandAllocations) {
            BigDecimal demandShare = demandShare(demandAllocation, coverage);
            for (PurchaseOrderSourceAllocation allocation : coverage.allocationsByPurchaseRequestItem()
                .getOrDefault(demandAllocation.purchaseRequestItemId(), List.of())) {
                ordered = ordered.add(nvl(allocation.getOrderedQuantity()).multiply(demandShare));
                if (allocation.getPurchaseOrderItemId() != null) {
                    poItemIds.add(allocation.getPurchaseOrderItemId());
                }
                PurchaseOrderItem poItem = coverage.purchaseOrderItems().get(allocation.getPurchaseOrderItemId());
                if (poItem == null) {
                    continue;
                }
                ProcurementLineType lineType = poItem.getLineType() == null ? ProcurementLineType.MATERIAL : poItem.getLineType();
                if (lineType == ProcurementLineType.MATERIAL) {
                    materialLine = true;
                    BigDecimal accepted = coverage.receiptLinesByPurchaseOrderItem()
                        .getOrDefault(poItem.getId(), List.of())
                        .stream()
                        .map(GoodsReceiptLine::getAcceptedQuantity)
                        .map(this::nvl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    receivedOrAccepted = receivedOrAccepted.add(attributedQuantity(allocation, accepted, coverage).multiply(demandShare));
                } else if (lineType == ProcurementLineType.WORK) {
                    serviceOrWorkLine = true;
                    List<WorkCompletionAct> workActs = coverage.workActsByPurchaseOrderItem()
                        .getOrDefault(poItem.getId(), List.of())
                        .stream()
                        .toList();
                    workCompletionActExists = workCompletionActExists || !workActs.isEmpty();
                    acceptedWorkCompletionActExists = acceptedWorkCompletionActExists || workActs.stream().anyMatch(this::isAcceptedWorkAct);
                    BigDecimal completed = workActs
                        .stream()
                        .filter(this::isAcceptedWorkAct)
                        .map(WorkCompletionAct::getCompletedQuantity)
                        .map(this::nvl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    receivedOrAccepted = receivedOrAccepted.add(attributedQuantity(allocation, completed, coverage).multiply(demandShare));
                } else {
                    serviceOrWorkLine = true;
                    List<ServiceExecution> executions = poItem.getServiceOrderId() == null
                        ? List.of()
                        : coverage.serviceExecutionsByServiceOrder().getOrDefault(poItem.getServiceOrderId(), List.of());
                    serviceExecutionExists = serviceExecutionExists || !executions.isEmpty();
                    activeServiceExecutionExists = activeServiceExecutionExists || executions.stream().anyMatch(this::isActiveServiceExecution);
                    completedServiceExecutionExists = completedServiceExecutionExists || executions.stream().anyMatch(this::isCompletedServiceExecution);
                    List<ServiceAcceptanceAct> acts = coverage.serviceActsByPurchaseOrder()
                        .getOrDefault(poItem.getPurchaseOrderId(), List.of())
                        .stream()
                        .filter(act -> poItem.getServiceOrderId() == null || Objects.equals(poItem.getServiceOrderId(), act.getServiceOrderId()))
                        .toList();
                    serviceAcceptanceActExists = serviceAcceptanceActExists || !acts.isEmpty();
                    acceptedServiceAcceptanceActExists = acceptedServiceAcceptanceActExists || acts.stream().anyMatch(this::isAcceptedServiceAct);
                    boolean accepted = acts.stream().anyMatch(this::isAcceptedServiceAct);
                    if (accepted) {
                        receivedOrAccepted = receivedOrAccepted.add(nvl(allocation.getOrderedQuantity()).multiply(demandShare));
                    }
                }
            }
        }
        BigDecimal departmentIssued = coverage.issueLinesByDemandPlanItem()
            .getOrDefault(item.getId(), List.of())
            .stream()
            .filter(this::isIssuedLine)
            .map(IssueRequestLine::getIssuedQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean missingLineage = !demandAllocations.isEmpty()
            && demandAllocations.stream().allMatch(allocation ->
                coverage.allocationsByPurchaseRequestItem().getOrDefault(allocation.purchaseRequestItemId(), List.of()).isEmpty());
        BigDecimal approved = nvl(item.getApprovedQuantity()).add(nvl(item.getStockFulfillmentQuantity()));
        boolean supplied = approved.signum() > 0
            && (materialLine ? departmentIssued.compareTo(approved) >= 0 : receivedOrAccepted.compareTo(approved) >= 0);
        return new ItemFacts(
            ordered,
            receivedOrAccepted,
            departmentIssued,
            missingLineage,
            materialLine,
            serviceOrWorkLine,
            serviceExecutionExists,
            activeServiceExecutionExists,
            completedServiceExecutionExists,
            serviceAcceptanceActExists,
            acceptedServiceAcceptanceActExists,
            workCompletionActExists,
            acceptedWorkCompletionActExists,
            supplied,
            poItemIds
        );
    }

    private BigDecimal demandShare(DemandPrAllocation allocation, CoverageContext coverage) {
        BigDecimal allocated = nvl(allocation.allocatedQuantity());
        if (allocated.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = nvl(coverage.demandAllocationTotalsByPurchaseRequestItem()
            .get(allocation.purchaseRequestItemId()));
        if (total.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return allocated.divide(total, 8, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal attributedQuantity(
        PurchaseOrderSourceAllocation allocation,
        BigDecimal purchaseOrderItemQuantity,
        CoverageContext coverage
    ) {
        BigDecimal allocated = nvl(allocation.getOrderedQuantity());
        BigDecimal totalAllocated = nvl(coverage.allocatedTotalsByPurchaseOrderItem().get(allocation.getPurchaseOrderItemId()));
        if (allocated.signum() <= 0 || purchaseOrderItemQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (totalAllocated.signum() <= 0) {
            return purchaseOrderItemQuantity.min(allocated);
        }
        BigDecimal share = purchaseOrderItemQuantity
            .multiply(allocated)
            .divide(totalAllocated, 4, java.math.RoundingMode.HALF_UP);
        return share.min(allocated);
    }

    private DemandPlanCoverageStatus coverageStatus(DemandPlanItem item, ItemFacts facts) {
        BigDecimal approved = nvl(item.getApprovedQuantity()).add(nvl(item.getStockFulfillmentQuantity()));
        if (facts.supplied()) {
            return DemandPlanCoverageStatus.SUPPLIED;
        }
        BigDecimal suppliedQuantity = facts.materialLine() ? facts.departmentIssued() : facts.receivedOrAccepted();
        if (approved.signum() > 0 && suppliedQuantity.signum() > 0) {
            return DemandPlanCoverageStatus.PARTIAL;
        }
        // Known stock fulfillment remains visible while procurement lineage is still incomplete.
        // sourceWarnings continues to report the missing procurement allocation independently.
        if (facts.missingRequiredLineage()) {
            return DemandPlanCoverageStatus.UNAVAILABLE;
        }
        if (facts.ordered().signum() > 0) {
            return DemandPlanCoverageStatus.ORDERED;
        }
        return DemandPlanCoverageStatus.NOT_STARTED;
    }

    private boolean isIssuedLine(IssueRequestLine line) {
        return line.getIssueRequest() != null
            && List.of(
                IssueRequestStatus.PARTIALLY_ISSUED.name(),
                IssueRequestStatus.ISSUED.name(),
                IssueRequestStatus.CLOSED.name()
            ).contains(line.getIssueRequest().getStatus())
            && nvl(line.getIssuedQuantity()).signum() > 0;
    }

    private List<RelatedDocumentRef> relatedDocuments(List<DemandPlanItem> items, CoverageContext coverage) {
        return distinctRefs(items.stream().flatMap(item -> relatedDocuments(item, coverage).stream()).toList());
    }

    private List<RelatedDocumentRef> relatedDocuments(
        DemandPlan plan,
        List<DemandPlanItem> items,
        CoverageContext coverage
    ) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        if (plan.getPlanningCycle() != null) {
            refs.add(new RelatedDocumentRef(
                "DEMAND_PLANNING_CYCLE",
                plan.getPlanningCycle().getId(),
                "demand-planning-cycles",
                plan.getPlanningCycle().getCode(),
                plan.getPlanningCycle().getName(),
                plan.getPlanningCycle().getStatus()
            ));
        }
        refs.addAll(relatedDocuments(items, coverage));
        return distinctRefs(refs);
    }

    private List<RelatedDocumentRef> relatedDocuments(DemandPlanItem item, CoverageContext coverage) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        PurchaseRequestItem purchaseRequestItem = item.getPurchaseRequestItemId() == null
            ? null
            : coverage.purchaseRequestItems().get(item.getPurchaseRequestItemId());
        if (purchaseRequestItem != null && purchaseRequestItem.getPurchaseRequest() != null) {
            refs.add(new RelatedDocumentRef(
                "PURCHASE_REQUEST",
                purchaseRequestItem.getPurchaseRequest().getId(),
                "purchase-requests",
                purchaseRequestItem.getPurchaseRequest().getCode(),
                purchaseRequestItem.getPurchaseRequest().getName(),
                purchaseRequestItem.getPurchaseRequest().getStatus()
            ));
        }
        ItemFacts facts = facts(item, coverage);
        for (UUID poItemId : facts.purchaseOrderItemIds()) {
            PurchaseOrderItem poItem = coverage.purchaseOrderItems().get(poItemId);
            if (poItem == null) {
                continue;
            }
            if (poItem.getPurchaseOrder() != null) {
                refs.add(new RelatedDocumentRef(
                    "PURCHASE_ORDER",
                    poItem.getPurchaseOrder().getId(),
                    "purchase-orders",
                    poItem.getPurchaseOrder().getCode(),
                    poItem.getPurchaseOrder().getName(),
                    poItem.getPurchaseOrder().getStatus()
                ));
            }
            coverage.receiptLinesByPurchaseOrderItem().getOrDefault(poItemId, List.of()).forEach(line ->
                refs.add(new RelatedDocumentRef("GOODS_RECEIPT", line.getGoodsReceiptId(), "goods-receipt", null, "Goods receipt", line.getStatus())));
            if (poItem.getServiceOrderId() != null) {
                coverage.serviceExecutionsByServiceOrder().getOrDefault(poItem.getServiceOrderId(), List.of())
                    .forEach(execution -> refs.add(new RelatedDocumentRef(
                        "SERVICE_EXECUTION",
                        execution.getId(),
                        "service-executions",
                        execution.getCode(),
                        execution.getName(),
                        execution.getStatus()
                    )));
            }
            coverage.serviceActsByPurchaseOrder().getOrDefault(poItem.getPurchaseOrderId(), List.of()).stream()
                .filter(act -> poItem.getServiceOrderId() == null || Objects.equals(poItem.getServiceOrderId(), act.getServiceOrderId()))
                .forEach(act -> refs.add(new RelatedDocumentRef(
                    "SERVICE_ACCEPTANCE_ACT", act.getId(), "service-acceptance-acts", act.getCode(), act.getName(), act.getStatus())));
            coverage.workActsByPurchaseOrderItem().getOrDefault(poItemId, List.of()).forEach(act ->
                refs.add(new RelatedDocumentRef("WORK_COMPLETION_ACT", act.getId(), "work-completion-acts", act.getCode(), act.getName(), act.getStatus())));
        }
        coverage.issueLinesByDemandPlanItem().getOrDefault(item.getId(), List.of()).stream()
            .filter(this::isIssuedLine)
            .map(IssueRequestLine::getIssueRequest)
            .filter(Objects::nonNull)
            .forEach(request -> refs.add(new RelatedDocumentRef(
                "ISSUE_REQUEST", request.getId(), "issue-requests", request.getCode(), request.getName(), request.getStatus())));
        return distinctRefs(refs);
    }

    private List<RelatedDocumentRef> documentsForStage(DemandPlanProcessStage stage, List<DemandPlanItem> items, CoverageContext coverage) {
        List<RelatedDocumentRef> all = relatedDocuments(items, coverage);
        return switch (stage) {
            case PURCHASE_REQUEST -> all.stream().filter(ref -> "PURCHASE_REQUEST".equals(ref.documentType())).toList();
            case PURCHASE_ORDER -> all.stream().filter(ref -> "PURCHASE_ORDER".equals(ref.documentType())).toList();
            case INBOUND_OR_EXECUTION -> all.stream().filter(ref -> "SERVICE_EXECUTION".equals(ref.documentType())).toList();
            case RECEIPT_OR_ACCEPTANCE -> all.stream().filter(ref -> List.of("GOODS_RECEIPT", "SERVICE_ACCEPTANCE_ACT", "WORK_COMPLETION_ACT").contains(ref.documentType())).toList();
            case DEPARTMENT_SUPPLY -> all.stream().filter(ref -> "ISSUE_REQUEST".equals(ref.documentType())).toList();
            default -> List.of();
        };
    }

    private String stageSummary(DemandPlanProcessStage stage, List<DemandPlanItem> items, CoverageContext coverage) {
        Counts counts = counts(items, coverage);
        return switch (stage) {
            case PURCHASE_REQUEST -> linkedPurchaseRequests(items, coverage).size() + " purchase request(s) linked";
            case SOURCING -> linkedPurchaseRequests(items, coverage).stream().filter(this::purchaseRequestReadyForSourcing).count()
                + " purchase request(s) ready for sourcing";
            case PURCHASE_ORDER -> counts.ordered() + " item(s) have purchase order allocation";
            case INBOUND_OR_EXECUTION -> items.stream().filter(item -> {
                ItemFacts facts = facts(item, coverage);
                return facts.serviceExecutionExists() || facts.serviceAcceptanceActExists() || facts.workCompletionActExists();
            }).count() + " item(s) have execution evidence";
            case RECEIPT_OR_ACCEPTANCE -> items.stream().filter(item -> {
                ItemFacts facts = facts(item, coverage);
                return facts.receivedOrAccepted().signum() > 0 || facts.serviceAcceptanceActExists() || facts.workCompletionActExists();
            }).count()
                + " item(s) have receipt or acceptance evidence";
            case DEPARTMENT_SUPPLY -> counts.full() + " of " + counts.total() + " item(s) are department supplied";
            default -> null;
        };
    }

    private List<String> supplierNames(List<DemandPlanItem> items, CoverageContext coverage) {
        return items.stream()
            .flatMap(item -> supplierNames(item, coverage).stream())
            .distinct()
            .sorted()
            .toList();
    }

    private List<String> supplierNames(DemandPlanItem item, CoverageContext coverage) {
        return facts(item, coverage).purchaseOrderItemIds().stream()
            .map(coverage.purchaseOrderItems()::get)
            .filter(Objects::nonNull)
            .map(PurchaseOrderItem::getPurchaseOrder)
            .filter(Objects::nonNull)
            .map(po -> po.getSupplier() == null
                ? null
                : firstNonBlank(po.getSupplier().getShortName(), po.getSupplier().getLegalName(), po.getSupplier().getName(), po.getSupplier().getCode()))
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    private List<String> issueActors(List<DemandPlanItem> items, CoverageContext coverage) {
        List<IssueRequest> requests = items.stream()
            .flatMap(item -> coverage.issueLinesByDemandPlanItem().getOrDefault(item.getId(), List.of()).stream())
            .filter(this::isIssuedLine)
            .map(IssueRequestLine::getIssueRequest)
            .filter(Objects::nonNull)
            .toList();
        Map<UUID, String> userNamesById = userNamesById(requests.stream()
            .map(IssueRequest::getReceiverUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));
        List<String> usernames = requests.stream()
            .map(IssueRequest::getUpdatedBy)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<String, String> userNamesByUsername = userNamesByUsername(usernames);
        return requests.stream()
            .map(request -> firstNonBlank(
                request.getReceiverUserId() == null ? null : userNamesById.get(request.getReceiverUserId()),
                request.getUpdatedBy() == null ? null : userNamesByUsername.get(normalizeUsername(request.getUpdatedBy())),
                request.getUpdatedBy()
            ))
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    private List<RelatedDocumentRef> distinctRefs(List<RelatedDocumentRef> refs) {
        Map<String, RelatedDocumentRef> distinct = new HashMap<>();
        for (RelatedDocumentRef ref : refs) {
            if (ref == null || ref.documentId() == null) {
                continue;
            }
            distinct.putIfAbsent(ref.documentType() + ":" + ref.documentId(), ref);
        }
        return new ArrayList<>(distinct.values());
    }

    private boolean isAcceptedServiceAct(ServiceAcceptanceAct act) {
        return act != null && acceptedServiceStatuses().contains(act.getStatus());
    }

    private boolean isAcceptedWorkAct(WorkCompletionAct act) {
        return act != null && acceptedWorkStatuses().contains(act.getStatus());
    }

    private boolean isActiveServiceExecution(ServiceExecution execution) {
        if (execution == null) {
            return false;
        }
        return List.of(
            ServiceExecutionStatus.IN_PROGRESS.name(),
            ServiceExecutionStatus.PAUSED.name(),
            ServiceExecutionStatus.UNDER_REVIEW.name()
        ).contains(execution.getStatus());
    }

    private boolean isCompletedServiceExecution(ServiceExecution execution) {
        if (execution == null) {
            return false;
        }
        return List.of(
            ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR.name(),
            ServiceExecutionStatus.ACCEPTED.name(),
            ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name()
        ).contains(execution.getStatus());
    }

    private List<String> serviceActStatuses() {
        return List.of(
            ServiceAcceptanceActStatus.DRAFT.name(),
            ServiceAcceptanceActStatus.SUBMITTED.name(),
            ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name(),
            ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name(),
            ServiceAcceptanceActStatus.FINANCE_REVIEW.name(),
            ServiceAcceptanceActStatus.APPROVED.name(),
            ServiceAcceptanceActStatus.REJECTED.name(),
            ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(),
            ServiceAcceptanceActStatus.PAID.name()
        );
    }

    private List<String> workActStatuses() {
        return List.of(
            WorkCompletionActStatus.DRAFT.name(),
            WorkCompletionActStatus.APPROVAL_PENDING.name(),
            WorkCompletionActStatus.APPROVED.name(),
            WorkCompletionActStatus.REJECTED.name(),
            WorkCompletionActStatus.SENT_TO_PAYMENT.name(),
            WorkCompletionActStatus.PAID.name()
        );
    }

    private List<String> acceptedServiceStatuses() {
        return List.of(
            ServiceAcceptanceActStatus.APPROVED.name(),
            ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(),
            ServiceAcceptanceActStatus.PAID.name()
        );
    }

    private List<String> acceptedWorkStatuses() {
        return List.of(
            WorkCompletionActStatus.APPROVED.name(),
            WorkCompletionActStatus.SENT_TO_PAYMENT.name(),
            WorkCompletionActStatus.PAID.name()
        );
    }

    private List<DemandPlanProcessStage> workspaceStages() {
        return List.of(
            DemandPlanProcessStage.DEMAND_PLAN,
            DemandPlanProcessStage.PLAN_APPROVAL,
            DemandPlanProcessStage.PURCHASE_REQUEST,
            DemandPlanProcessStage.SOURCING,
            DemandPlanProcessStage.PURCHASE_ORDER,
            DemandPlanProcessStage.INBOUND_OR_EXECUTION,
            DemandPlanProcessStage.RECEIPT_OR_ACCEPTANCE,
            DemandPlanProcessStage.DEPARTMENT_SUPPLY
        );
    }

    private String userDisplayName(User user) {
        return firstNonBlank(user.getFullName(), user.getName(), user.getUsername());
    }

    private String ownerFullName(String username) {
        String normalized = blank(username);
        if (normalized == null) {
            return null;
        }
        String tenantId = TenantContext.currentTenant();
        return userRepository
            .findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
                tenantId,
                CompanyContext.currentCompany(),
                normalized)
            .or(() -> userRepository
                .findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(
                    tenantId,
                    normalized))
            .map(User::getFullName)
            .map(this::blank)
            .orElse(null);
    }

    private Map<String, String> userFullNamesByUsername(Collection<String> usernames) {
        Set<String> normalizedUsernames = usernames.stream()
            .map(this::blank)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        if (normalizedUsernames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        normalizedUsernames.forEach(username -> {
            String fullName = ownerFullName(username);
            if (fullName != null) {
                names.put(normalizeUsername(username), fullName);
            }
        });
        return names;
    }

    private Map<String, String> userNamesByUsername(Collection<String> usernames) {
        Set<String> normalizedUsernames = usernames.stream()
            .map(this::blank)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        if (normalizedUsernames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        normalizedUsernames.forEach(username -> userRepository
            .resolveActiveUserByUsername(TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
            .ifPresent(user -> names.put(normalizeUsername(username), userDisplayName(user))));
        return names;
    }
    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "" : authentication.getName();
    }

    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority() == null ? "" : authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .collect(Collectors.toSet());
    }

    private String summaryItemType(DemandPlan plan) {
        return plan != null && plan.getRequestType() != null && plan.getRequestType().isServiceStyle()
            ? "SERVICE"
            : "MATERIAL";
    }

    private String summaryItemName(DemandPlan plan, DemandPlanItem item, String itemType) {
        if ("SERVICE".equals(itemType)) {
            return firstNonBlank(item.getMaterial() == null ? null : item.getMaterial().getName(), item.getName());
        }
        return item.getMaterial() == null ? item.getName() : item.getMaterial().getName();
    }

    private String commonCurrency(Set<String> currencies) {
        return currencies == null || currencies.size() != 1 ? null : currencies.iterator().next();
    }

    private record MatchingPlans(
        List<DemandPlan> plans,
        Map<UUID, List<DemandPlanItem>> itemsByPlan,
        CoverageContext coverage,
        long totalElements
    ) {
    }

    private class SummaryAccumulator {
        private long count;
        private BigDecimal plannedAmount = BigDecimal.ZERO;
        private BigDecimal approvedAmount = BigDecimal.ZERO;
        private final Set<String> currencies = new HashSet<>();

        void addPlan(DemandPlan plan) {
            count++;
            plannedAmount = plannedAmount.add(nvl(plan.getTotalPlannedAmount()));
            approvedAmount = approvedAmount.add(nvl(plan.getTotalApprovedAmount()));
            String currency = blank(plan.getCurrencyCode());
            if (currency != null) {
                currencies.add(currency);
            }
        }

        SummaryTotal toTotal(long withApprovalFlowCount) {
            return new SummaryTotal(count, plannedAmount, approvedAmount, commonCurrency(currencies), withApprovalFlowCount);
        }
    }

    private final class StatusSummaryAccumulator extends SummaryAccumulator {
        private final String status;
        private final Map<SummaryItemKey, ItemSummaryAccumulator> items = new HashMap<>();

        private StatusSummaryAccumulator(String status) {
            this.status = status;
        }

        String status() {
            return status;
        }

        void addItem(DemandPlan plan, DemandPlanItem item, ItemFacts facts) {
            String itemType = summaryItemType(plan);
            UUID materialId = item.getMaterialId();
            UUID serviceId = "SERVICE".equals(itemType) ? item.getMaterialId() : null;
            String name = summaryItemName(plan, item, itemType);
            String materialName = "MATERIAL".equals(itemType) ? name : null;
            String serviceName = "SERVICE".equals(itemType) ? name : null;
            String unit = localizedUnitName(item);
            SummaryItemKey key = new SummaryItemKey(itemType, materialId, serviceId);
            items.computeIfAbsent(key, ignored -> new ItemSummaryAccumulator(itemType, materialId, materialName, serviceId, serviceName, unit))
                .add(plan, item, facts);
        }

        StatusSummaryRow toRow() {
            List<ItemSummary> summaries = items.values().stream()
                .sorted(Comparator.comparing(ItemSummaryAccumulator::displayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(ItemSummaryAccumulator::toSummary)
                .toList();
            SummaryTotal total = toTotal(0);
            return new StatusSummaryRow(status, total.count(), total.plannedAmount(), total.approvedAmount(), total.currencyCode(), summaries);
        }
    }

    private final class ItemSummaryAccumulator {
        private final String itemType;
        private final UUID materialId;
        private final String materialName;
        private final UUID serviceId;
        private final String serviceName;
        private final String unit;
        private final Set<UUID> planIds = new HashSet<>();
        private BigDecimal requestedQuantity = BigDecimal.ZERO;
        private BigDecimal approvedQuantity = BigDecimal.ZERO;
        private BigDecimal reservedQuantity = BigDecimal.ZERO;
        private BigDecimal orderedQuantity = BigDecimal.ZERO;
        private BigDecimal receivedOrAcceptedQuantity = BigDecimal.ZERO;
        private BigDecimal departmentIssuedQuantity = BigDecimal.ZERO;
        private BigDecimal plannedAmount = BigDecimal.ZERO;

        private ItemSummaryAccumulator(
            String itemType,
            UUID materialId,
            String materialName,
            UUID serviceId,
            String serviceName,
            String unit
        ) {
            this.itemType = itemType;
            this.materialId = materialId;
            this.materialName = materialName;
            this.serviceId = serviceId;
            this.serviceName = serviceName;
            this.unit = unit;
        }

        void add(DemandPlan plan, DemandPlanItem item, ItemFacts facts) {
            planIds.add(plan.getId());
            requestedQuantity = requestedQuantity.add(nvl(item.getRequestedQuantity()));
            approvedQuantity = approvedQuantity.add(visibleApprovedQuantity(item));
            reservedQuantity = reservedQuantity.add(nvl(item.getReservedQuantity()));
            orderedQuantity = orderedQuantity.add(nvl(facts.ordered()));
            receivedOrAcceptedQuantity = receivedOrAcceptedQuantity.add(nvl(facts.receivedOrAccepted()));
            departmentIssuedQuantity = departmentIssuedQuantity.add(nvl(facts.departmentIssued()));
            plannedAmount = plannedAmount.add(nvl(item.getEstimatedAmount()));
        }

        String displayName() {
            return firstNonBlank(materialName, serviceName);
        }

        ItemSummary toSummary() {
            return new ItemSummary(
                itemType,
                materialId,
                materialName,
                serviceId,
                serviceName,
                unit,
                requestedQuantity,
                approvedQuantity,
                reservedQuantity,
                orderedQuantity,
                receivedOrAcceptedQuantity,
                departmentIssuedQuantity,
                plannedAmount,
                planIds.size()
            ).withUnitShortName(com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unit));
        }
    }

    private record SummaryItemKey(
        String itemType,
        UUID materialId,
        UUID serviceId
    ) {
    }

    public record Filter(
        DemandPlanWorkspaceView view,
        String search,
        Integer planYear,
        UUID departmentId,
        List<String> status,
        DemandPlanProcessStage processStage,
        UUID planningCycleId,
        String requestType,
        String periodType,
        Integer quarter,
        String ownerUsername,
        UUID materialId
    ) {
        static Filter empty() {
            return new Filter(DemandPlanWorkspaceView.ALL, null, null, null, List.of(), null, null, null, null, null, null, null);
        }
    }

    private record Counts(int total, int ordered, int partial, int full) {
    }

    private record CoverageContext(
        Map<UUID, List<DemandPrAllocation>> demandAllocationsByDemandItem,
        Map<UUID, BigDecimal> demandAllocationTotalsByPurchaseRequestItem,
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPurchaseRequestItem,
        Map<UUID, PurchaseOrderItem> purchaseOrderItems,
        Map<UUID, List<GoodsReceiptLine>> receiptLinesByPurchaseOrderItem,
        Map<UUID, List<IssueRequestLine>> issueLinesByDemandPlanItem,
        Map<UUID, List<ServiceAcceptanceAct>> serviceActsByPurchaseOrder,
        Map<UUID, List<ServiceExecution>> serviceExecutionsByServiceOrder,
        Map<UUID, List<WorkCompletionAct>> workActsByPurchaseOrderItem,
        Map<UUID, BigDecimal> allocatedTotalsByPurchaseOrderItem,
        Map<UUID, PurchaseRequestItem> purchaseRequestItems
    ) {
    }

    private record DemandPrAllocation(
        UUID demandPlanItemId,
        UUID purchaseRequestItemId,
        BigDecimal allocatedQuantity,
        BigDecimal allocatedAmount
    ) {
    }

    private record ItemFacts(
        BigDecimal ordered,
        BigDecimal receivedOrAccepted,
        BigDecimal departmentIssued,
        boolean missingRequiredLineage,
        boolean materialLine,
        boolean serviceOrWorkLine,
        boolean serviceExecutionExists,
        boolean activeServiceExecutionExists,
        boolean completedServiceExecutionExists,
        boolean serviceAcceptanceActExists,
        boolean acceptedServiceAcceptanceActExists,
        boolean workCompletionActExists,
        boolean acceptedWorkCompletionActExists,
        boolean supplied,
        Set<UUID> purchaseOrderItemIds
    ) {
    }
}
