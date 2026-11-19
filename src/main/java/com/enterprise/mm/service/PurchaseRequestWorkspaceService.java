package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.ProcurementAwardOutcomeLine;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.DemandPlanStageState;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseRequestCoverageStatus;
import com.enterprise.mm.enums.PurchaseRequestProcessStage;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey;
import com.enterprise.mm.enums.PurchaseRequestWorkspaceView;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.ApprovalStep;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.ApprovalTimeline;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.AvailableActions;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.FulfillmentSummary;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.ItemSummary;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.LineCoverage;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.ProcessStage;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestAction;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestHeader;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceDetail;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceRow;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceSummary;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.RelatedDocumentField;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.RelatedDocumentRef;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.SourceWarning;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.StatusSummaryRow;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.SummaryTotal;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.StatusTimelineItem;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeLineRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseRequestWorkspaceService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository itemRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final RFQRepository rfqRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final ProcurementAwardOutcomeLineRepository awardOutcomeLineRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final ProcurementMethodDecisionRepository methodDecisionRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementCampaignRepository campaignRepository;
    private final CommissionRepository commissionRepository;
    private final SupplierRepository supplierRepository;
    private final RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final UserRepository userRepository;
    private final RoleDataScopeService roleDataScopeService;

    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequestWorkspaceRow> worklist(Filter filter, Pageable pageable) {
        Filter effective = filter == null ? Filter.empty() : filter;
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        boolean resolvedFiltering = requiresResolvedFiltering(effective);
        Pageable queryPageable = scope.hasDocumentScope() || resolvedFiltering ? Pageable.unpaged() : pageable;
        Page<PurchaseRequest> page = purchaseRequestRepository.findAll(spec(effective), queryPageable);
        List<PurchaseRequest> scoped = page.getContent().stream()
            .filter(request -> roleDataScopeService.canAccessDocument(
                request.getCreatedBy(),
                request.getRequestedBy(),
                request.getDepartmentId()))
            .toList();
        Map<UUID, List<PurchaseRequestItem>> itemsByRequest = itemsByRequest(scoped);
        CoverageContext coverage = coverageContext(
            itemsByRequest.values().stream().flatMap(Collection::stream).toList(),
            scoped.stream().map(PurchaseRequest::getId).toList());
        Map<UUID, List<RFQ>> rfqsByPurchaseRequest = rfqsByPurchaseRequestIds(scoped.stream().map(PurchaseRequest::getId).toList());
        Map<UUID, Long> rfqCounts = rfqCounts(rfqsByPurchaseRequest);
        Map<UUID, Long> purchaseOrderCounts = purchaseOrderCounts(scoped);
        if (resolvedFiltering) {
            scoped = scoped.stream()
                .filter(request -> matchesResolvedFilter(
                    effective,
                    request,
                    itemsByRequest.getOrDefault(request.getId(), List.of()),
                    coverage))
                .toList();
        }
        List<PurchaseRequestWorkspaceRow> rows = scoped.stream()
            .map(request -> row(
                request,
                itemsByRequest.getOrDefault(request.getId(), List.of()),
                coverage,
                rfqCounts.getOrDefault(request.getId(), 0L),
                purchaseOrderCounts.getOrDefault(request.getId(), 0L)))
            .toList();
        if (scope.hasDocumentScope() || resolvedFiltering) {
            return PageResponse.from(roleDataScopeService.page(rows, pageable));
        }
        return PageResponse.from(new PageImpl<>(rows, pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public PurchaseRequestWorkspaceSummary summary(Filter filter) {
        MatchingRequests matching = matchingRequests(filter == null ? Filter.empty() : filter, Pageable.unpaged());
        Map<String, StatusSummaryAccumulator> byStatus = new HashMap<>();
        SummaryAccumulator total = new SummaryAccumulator();
        matching.requests().forEach(request -> {
            String status = firstNonBlank(request.getStatus(), "UNKNOWN");
            StatusSummaryAccumulator row = byStatus.computeIfAbsent(status, ignored -> new StatusSummaryAccumulator(status));
            row.addRequest(request);
            total.addRequest(request);
            matching.itemsByRequest().getOrDefault(request.getId(), List.of())
                .forEach(item -> row.addItem(request, item, facts(item, matching.coverage()), matching.coverage()));
        });
        List<StatusSummaryRow> rows = byStatus.values().stream()
            .sorted(Comparator.comparing(StatusSummaryAccumulator::status))
            .map(StatusSummaryAccumulator::toRow)
            .toList();
        return new PurchaseRequestWorkspaceSummary(rows, total.toTotal(withApprovalFlowCount(matching.requests())));
    }

    private MatchingRequests matchingRequests(Filter filter, Pageable pageable) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        boolean resolvedFiltering = requiresResolvedFiltering(filter);
        Pageable queryPageable = scope.hasDocumentScope() || resolvedFiltering ? Pageable.unpaged() : pageable;
        Page<PurchaseRequest> page = purchaseRequestRepository.findAll(spec(filter), queryPageable);
        List<PurchaseRequest> scoped = page.getContent().stream()
            .filter(request -> roleDataScopeService.canAccessDocument(
                request.getCreatedBy(),
                request.getRequestedBy(),
                request.getDepartmentId()))
            .toList();
        Map<UUID, List<PurchaseRequestItem>> itemsByRequest = itemsByRequest(scoped);
        CoverageContext coverage = coverageContext(
            itemsByRequest.values().stream().flatMap(Collection::stream).toList(),
            scoped.stream().map(PurchaseRequest::getId).toList());
        if (resolvedFiltering) {
            scoped = scoped.stream()
                .filter(request -> matchesResolvedFilter(
                    filter,
                    request,
                    itemsByRequest.getOrDefault(request.getId(), List.of()),
                    coverage))
                .toList();
        }
        return new MatchingRequests(scoped, itemsByRequest, coverage, page.getTotalElements());
    }

    private long withApprovalFlowCount(List<PurchaseRequest> requests) {
        List<UUID> ids = requests.stream()
            .map(PurchaseRequest::getId)
            .filter(Objects::nonNull)
            .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return approvalInstanceRepository.countByTenantIdAndCompanyIdAndModuleKeyAndEntityIdInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ids
        );
    }

    @Transactional(readOnly = true)
    public PurchaseRequestWorkspaceDetail detail(UUID id) {
        PurchaseRequest request = purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(CompanyContext.currentCompany(), candidate.getCompanyId()))
            .filter(candidate -> roleDataScopeService.canAccessDocument(
                candidate.getCreatedBy(),
                candidate.getRequestedBy(),
                candidate.getDepartmentId()))
            .orElseThrow(() -> new NotFoundException("PurchaseRequest not found: " + id));
        List<PurchaseRequestItem> items = itemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .stream()
            .sorted(Comparator.comparing(PurchaseRequestItem::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        CoverageContext coverage = coverageContext(items, List.of(request.getId()));
        LocalDateTime generatedAt = LocalDateTime.now();
        return new PurchaseRequestWorkspaceDetail(
            header(request),
            fulfillmentSummary(items, coverage, generatedAt),
            stages(request, items, coverage),
            items.stream().map(item -> lineCoverage(item, coverage)).toList(),
            approvalTimeline(request),
            statusHistory(request),
            relatedDocuments(request, items, coverage),
            actions(request, items, coverage),
            generatedAt,
            sourceWarnings(request, items, coverage)
        );
    }

    private Specification<PurchaseRequest> spec(Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("department", JoinType.LEFT);
                root.fetch("demandPlan", JoinType.LEFT);
                query.distinct(true);
            }
            String search = blank(filter.search());
            if (search != null) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                var department = root.join("department", JoinType.LEFT);
                var demandPlan = root.join("demandPlan", JoinType.LEFT);
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), like),
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(department.get("name")), like),
                    cb.like(cb.lower(demandPlan.get("code")), like),
                    cb.like(cb.lower(demandPlan.get("name")), like)
                ));
            }
            List<String> statuses = statuses(filter);
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (filter.departmentId() != null) {
                predicates.add(cb.equal(root.get("departmentId"), filter.departmentId()));
            }
            if (filter.demandPlanId() != null) {
                predicates.add(cb.equal(root.get("demandPlanId"), filter.demandPlanId()));
            }
            if (blank(filter.requestType()) != null) {
                predicates.add(cb.equal(
                    root.get("requestType"),
                    com.enterprise.mm.enums.RequestType.valueOf(filter.requestType().trim().toUpperCase(Locale.ROOT))));
            }
            String requester = blank(filter.requestedBy());
            if (requester != null) {
                predicates.add(cb.equal(cb.lower(root.get("requestedBy")), requester.toLowerCase(Locale.ROOT)));
            }
            if (filter.view() == PurchaseRequestWorkspaceView.MY) {
                predicates.add(cb.equal(cb.lower(root.get("requestedBy")), currentUsername().toLowerCase(Locale.ROOT)));
            }
            if (filter.materialId() != null && query != null) {
                var itemSubquery = query.subquery(UUID.class);
                var item = itemSubquery.from(PurchaseRequestItem.class);
                itemSubquery.select(item.get("purchaseRequestId")).where(
                    cb.equal(item.get("tenantId"), TenantContext.currentTenant()),
                    cb.equal(item.get("companyId"), CompanyContext.currentCompany()),
                    cb.isFalse(item.get("isDeleted")),
                    cb.or(
                        cb.equal(item.get("materialId"), filter.materialId()),
                        cb.equal(item.get("serviceRequestId"), filter.materialId())
                    )
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
            case DRAFT -> List.of(PurchaseRequestStatus.DRAFT.name(), PurchaseRequestStatus.RETURNED.name());
            case ON_APPROVAL -> List.of(PurchaseRequestStatus.APPROVAL_PENDING.name());
            case IN_SOURCING -> List.of(
                PurchaseRequestStatus.APPROVED.name(),
                PurchaseRequestStatus.RFQ_CREATED.name(),
                PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
                PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name());
            case IN_PROCUREMENT -> List.of(
                PurchaseRequestStatus.PO_CREATED.name(),
                PurchaseRequestStatus.CONVERTED_TO_PO.name());
            case FULFILLED -> List.of();
            case ALL, MY -> List.of();
        };
    }

    private boolean requiresResolvedFiltering(Filter filter) {
        return filter.processStage() != null || filter.view() == PurchaseRequestWorkspaceView.FULFILLED;
    }

    private boolean matchesResolvedFilter(
        Filter filter,
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        if (filter.processStage() != null && currentStage(request, items, coverage) != filter.processStage()) {
            return false;
        }
        if (filter.view() == PurchaseRequestWorkspaceView.FULFILLED) {
            return isFulfilled(items, coverage);
        }
        return true;
    }

    private Map<UUID, List<PurchaseRequestItem>> itemsByRequest(List<PurchaseRequest> requests) {
        List<UUID> ids = requests.stream().map(PurchaseRequest::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
            .stream()
            .collect(Collectors.groupingBy(PurchaseRequestItem::getPurchaseRequestId));
    }

    private Map<UUID, Long> rfqCounts(Map<UUID, List<RFQ>> rfqsByPurchaseRequest) {
        return rfqsByPurchaseRequest.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> (long) entry.getValue().size()));
    }

    private Map<UUID, Long> purchaseOrderCounts(List<PurchaseRequest> requests) {
        List<UUID> ids = requests.stream().map(PurchaseRequest::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return purchaseOrderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), ids)
            .stream()
            .collect(Collectors.groupingBy(PurchaseOrder::getPurchaseRequestId, Collectors.counting()));
    }

    private PurchaseRequestWorkspaceRow row(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage,
        long rfqCount,
        long purchaseOrderCount
    ) {
        Counts counts = counts(items, coverage);
        DemandPlan demandPlan = request.getDemandPlan();
        return new PurchaseRequestWorkspaceRow(
            request.getId(),
            request.getCode(),
            displayName(request.getName()),
            request.getRequestType() == null ? null : request.getRequestType().name(),
            request.getStatus(),
            request.getDepartmentId(),
            request.getDepartment() == null ? null : request.getDepartment().getName(),
            request.getDemandPlanId(),
            demandPlan == null ? null : demandPlan.getCode(),
            demandPlan == null ? null : displayName(demandPlan.getName()),
            request.getRequestedBy(),
            request.getRequiredDate(),
            request.getCurrencyCode(),
            nvl(request.getTotalAmount()),
            currentStage(request, items, coverage).name(),
            currentState(request).name(),
            counts.total(),
            counts.ordered(),
            counts.partial(),
            counts.full(),
            rfqCount,
            purchaseOrderCount,
            request.getUpdatedAt(),
            primaryAction(request, items, coverage)
        );
    }

    private PurchaseRequestHeader header(PurchaseRequest request) {
        DemandPlan demandPlan = request.getDemandPlan();
        return new PurchaseRequestHeader(
            request.getId(),
            request.getCode(),
            displayName(request.getName()),
            request.getRequestType() == null ? null : request.getRequestType().name(),
            request.getStatus(),
            request.getDepartmentId(),
            request.getDepartment() == null ? null : request.getDepartment().getName(),
            request.getDemandPlanId(),
            demandPlan == null ? null : demandPlan.getCode(),
            demandPlan == null ? null : displayName(demandPlan.getName()),
            request.getRequestedBy(),
            request.getRequiredDate(),
            request.getCurrencyCode(),
            nvl(request.getTotalAmount()),
            request.getVersion()
        );
    }

    private FulfillmentSummary fulfillmentSummary(
        List<PurchaseRequestItem> items,
        CoverageContext coverage,
        LocalDateTime generatedAt
    ) {
        Counts counts = counts(items, coverage);
        return new FulfillmentSummary(
            counts.total(),
            counts.ordered(),
            counts.partial(),
            counts.full(),
            generatedAt,
            supplierNames(items, coverage)
        );
    }

    private List<ProcessStage> stages(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        PurchaseRequestProcessStage current = currentStage(request, items, coverage);
        List<ProcessStage> result = new ArrayList<>();
        for (PurchaseRequestProcessStage stage : workspaceStages()) {
            DemandPlanStageState state;
            if (stage == PurchaseRequestProcessStage.DEMAND_PLAN && request.getDemandPlanId() == null) {
                state = DemandPlanStageState.UNSTARTED;
            } else if (stage.ordinal() < current.ordinal()) {
                state = DemandPlanStageState.COMPLETED;
            } else if (stage == current) {
                state = currentState(request);
            } else {
                state = DemandPlanStageState.NOT_STARTED;
            }
            result.add(new ProcessStage(
                stage.name(),
                state.name(),
                stage == current,
                null,
                null,
                null,
                null,
                stageSummary(stage, request, items, coverage),
                documentsForStage(stage, request, items, coverage)
            ));
        }
        return result;
    }

    private LineCoverage lineCoverage(PurchaseRequestItem item, CoverageContext coverage) {
        ItemFacts facts = facts(item, coverage);
        DemandPlanItem demandItem = coverage.demandPlanItemsByPurchaseRequestItemId().get(item.getId());
        DemandPlan demandPlan = demandItem == null ? null : coverage.demandPlansById().get(demandItem.getDemandPlanId());
        return new LineCoverage(
            item.getId(),
            item.getCode(),
            displayName(item.getName()),
            item.getLineType() == null ? null : item.getLineType().name(),
            item.getMaterialId() == null && demandItem != null ? demandItem.getMaterialId() : item.getMaterialId(),
            materialName(item, demandItem),
            unitName(item, demandItem),
            nvl(item.getQuantity()),
            facts.ordered(),
            facts.receivedOrAccepted(),
            coverageStatus(item, facts).name(),
            demandItem == null ? null : demandItem.getId(),
            demandItem == null ? null : demandItem.getCode(),
            demandPlan == null ? null : demandPlan.getId(),
            demandPlan == null ? null : demandPlan.getCode(),
            item.fulfillmentDate(item.getPurchaseRequest()),
            supplierNames(item, coverage),
            relatedDocuments(item, coverage)
        ).withUnitShortName(unitDisplay.shortName(item, item.getUnit(), item.getMaterial()));
    }

    private String materialName(PurchaseRequestItem item, DemandPlanItem demandItem) {
        Material material = item.getMaterial() == null && demandItem != null ? demandItem.getMaterial() : item.getMaterial();
        if (material != null) {
            return displayName(firstNonBlank(material.getName(), material.getCode(), material.getArticle()));
        }
        Material demandMaterial = demandItem == null ? null : demandItem.getMaterial();
        if (demandMaterial != null) {
            return displayName(firstNonBlank(demandMaterial.getName(), demandMaterial.getCode(), demandMaterial.getArticle()));
        }
        return displayName(firstNonBlank(item.getServiceDescription(), item.getServiceCategory(), item.getName()));
    }

    private String unitName(PurchaseRequestItem item, DemandPlanItem demandItem) {
        String explicitUnit = blank(item.getUnit());
        if (explicitUnit != null) {
            return explicitUnit;
        }
        Material material = item.getMaterial() == null && demandItem != null ? demandItem.getMaterial() : item.getMaterial();
        String materialUnit = unitName(material);
        if (materialUnit != null) {
            return materialUnit;
        }
        return unitName(demandItem == null ? null : demandItem.getMaterial());
    }

    private String unitName(Material material) {
        UnitOfMeasure unit = material == null ? null : material.getUnitOfMeasure();
        return unit == null
            ? null
            : firstNonBlank(
                unit.getMeasurementNameShort(),
                unit.getCode(),
                unit.getMeasurementNameLong(),
                unit.getName());
    }

    private ApprovalTimeline approvalTimeline(PurchaseRequest request) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                PurchaseRequestApprovalAdapter.MODULE_KEY,
                request.getId())
            .orElse(null);
        if (instance == null) {
            return new ApprovalTimeline(PurchaseRequestApprovalAdapter.MODULE_KEY, null, "NOT_STARTED", List.of());
        }
        List<ApprovalTask> tasks = approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), instance.getId());
        List<ApprovalHistory> histories = approvalHistoryRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByActionAtAsc(
            TenantContext.currentTenant(), instance.getId());
        Map<Integer, ApprovalHistory> latestHistoryByStep = histories.stream()
            .filter(history -> history.getFromStepOrder() != null || history.getToStepOrder() != null)
            .collect(Collectors.toMap(
                history -> history.getFromStepOrder() == null ? history.getToStepOrder() : history.getFromStepOrder(),
                Function.identity(),
                (first, second) -> second));
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
            task.getAssigneeRole());
        String actingUserRaw = firstNonBlank(task.getActedBy(), history == null ? null : history.getActor());
        String actingUser = firstNonBlank(
            actingUserRaw == null ? null : userNamesByUsername.get(normalizeUsername(actingUserRaw)),
            actingUserRaw);
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

    private List<StatusTimelineItem> statusHistory(PurchaseRequest request) {
        List<AuditLog> logs = auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "PurchaseRequest", request.getId());
        Map<String, String> userNamesByUsername = fullNameByFullName(logs.stream().map(AuditLog::getFullName).toList());
        return logs.stream()
            .map(log -> new StatusTimelineItem(
                log.getId(),
                log.getAction(),
                actorName(log.getFullName(), userNamesByUsername),
                log.getDetails(),
                log.getOccurredAt()))
            .toList();
    }

    private List<RelatedDocumentRef> relatedDocuments(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        if (request.getDemandPlan() != null) {
            refs.add(documentRef(
                "DEMAND_PLAN",
                request.getDemandPlan().getId(),
                "demand-plans",
                request.getDemandPlan().getCode(),
                request.getDemandPlan().getName(),
                request.getDemandPlan().getStatus()));
        }
        procurementMethodDocuments(request, coverage).forEach(ref -> {
            if (refs.stream().noneMatch(existing -> Objects.equals(existing.documentId(), ref.documentId())
                && Objects.equals(existing.documentType(), ref.documentType()))) {
                refs.add(ref);
            }
        });
        coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of()).forEach(rfq -> refs.add(documentRef(
            "RFQ", rfq.getId(), "rfq", rfq.getCode(), rfq.getName(), rfq.getStatus())));
        coverage.purchaseOrdersByPurchaseRequestId().getOrDefault(request.getId(), List.of())
            .forEach(po -> refs.add(documentRef(
                "PURCHASE_ORDER", po.getId(), "purchase-orders", po.getCode(), po.getName(), po.getStatus())));
        items.forEach(item -> relatedDocuments(item, coverage).forEach(ref -> {
            if (refs.stream().noneMatch(existing -> Objects.equals(existing.documentId(), ref.documentId())
                && Objects.equals(existing.documentType(), ref.documentType()))) {
                refs.add(ref);
            }
        }));
        return refs;
    }

    private List<RelatedDocumentRef> relatedDocuments(PurchaseRequestItem item, CoverageContext coverage) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        DemandPlanItem demandItem = coverage.demandPlanItemsByPurchaseRequestItemId().get(item.getId());
        if (demandItem != null) {
            DemandPlan demandPlan = coverage.demandPlansById().get(demandItem.getDemandPlanId());
            refs.add(documentRef(
                "DEMAND_PLAN_ITEM",
                demandItem.getId(),
                "demand-plan-items",
                demandItem.getCode(),
                demandItem.getName(),
                demandItem.getStatus()));
            if (demandPlan != null) {
                refs.add(documentRef(
                    "DEMAND_PLAN",
                    demandPlan.getId(),
                    "demand-plans",
                    demandPlan.getCode(),
                    demandPlan.getName(),
                    demandPlan.getStatus()));
            }
        }
        for (PurchaseOrderSourceAllocation allocation : coverage.allocationsByPurchaseRequestItemId()
            .getOrDefault(item.getId(), List.of())) {
            PurchaseOrderItem poItem = coverage.purchaseOrderItems().get(allocation.getPurchaseOrderItemId());
            if (poItem == null || poItem.getPurchaseOrder() == null) {
                continue;
            }
            PurchaseOrder purchaseOrder = poItem.getPurchaseOrder();
            refs.add(documentRef(
                "PURCHASE_ORDER",
                purchaseOrder.getId(),
                "purchase-orders",
                purchaseOrder.getCode(),
                purchaseOrder.getName(),
                purchaseOrder.getStatus()));
            refs.add(documentRef(
                "PURCHASE_ORDER_ITEM",
                poItem.getId(),
                "purchase-order-items",
                poItem.getCode(),
                poItem.getName(),
                poItem.getStatus()));
        }
        return refs;
    }

    private AvailableActions actions(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        PurchaseRequestAction primary = primaryAction(request, items, coverage);
        List<PurchaseRequestAction> secondary = new ArrayList<>();
        List<PurchaseRequestAction> overflow = new ArrayList<>();
        String status = request.getStatus();
        if (PurchaseRequestStatus.DRAFT.name().equals(status) || PurchaseRequestStatus.RETURNED.name().equals(status)) {
            secondary.add(commandAction(
                PurchaseRequestWorkspaceActionKey.EDIT,
                canOriginatePurchaseRequest(),
                false,
                canOriginatePurchaseRequest() ? null : "purchase_request.action.forbidden"));
        }
        if (canSourcePurchaseRequest() && List.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
        ).contains(status)) {
            RelatedDocumentRef pmd = openPmdActionTarget(request, coverage);
            secondary.add(pmd == null
                ? commandAction(
                    PurchaseRequestWorkspaceActionKey.CREATE_PMD,
                    canMutatePurchaseRequest(),
                    false,
                    canMutatePurchaseRequest() ? null : "purchase_request.action.forbidden")
                : navigationAction(PurchaseRequestWorkspaceActionKey.OPEN_PMD, pmd));
            RelatedDocumentRef rfq = openRfqActionTarget(request, coverage);
            if (rfq != null) {
                secondary.add(navigationAction(PurchaseRequestWorkspaceActionKey.OPEN_RFQ, rfq));
            }
        }
        if (request.getDemandPlanId() != null) {
            secondary.add(navigationAction(
                PurchaseRequestWorkspaceActionKey.OPEN_DEMAND_PLAN,
                new RelatedDocumentRef(
                    "DEMAND_PLAN",
                    request.getDemandPlanId(),
                    "demand-plans",
                    request.getDemandPlan() == null ? null : request.getDemandPlan().getCode(),
                    request.getDemandPlan() == null ? null : request.getDemandPlan().getName(),
                    request.getDemandPlan() == null ? null : request.getDemandPlan().getStatus())));
        }
        if (PurchaseRequestStatus.DRAFT.name().equals(status)) {
            overflow.add(commandAction(
                PurchaseRequestWorkspaceActionKey.DELETE,
                canOriginatePurchaseRequest(),
                true,
                canOriginatePurchaseRequest() ? null : "purchase_request.action.forbidden"));
        }
        if (!PurchaseRequestStatus.CANCELLED.name().equals(status)) {
            overflow.add(commandAction(
                PurchaseRequestWorkspaceActionKey.CANCEL,
                canMutatePurchaseRequest(),
                true,
                canMutatePurchaseRequest() ? null : "purchase_request.action.forbidden"));
        }
        return new AvailableActions(primary, secondary, overflow);
    }

    private PurchaseRequestAction primaryAction(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        String status = request.getStatus();
        if (PurchaseRequestStatus.DRAFT.name().equals(status) || PurchaseRequestStatus.RETURNED.name().equals(status)) {
            boolean ready = !items.isEmpty();
            return commandAction(
                PurchaseRequestWorkspaceActionKey.SUBMIT,
                canOriginatePurchaseRequest() && ready,
                false,
                !canOriginatePurchaseRequest() ? "purchase_request.action.forbidden" : ready ? null : "purchase_request.action.no_items");
        }
        if (PurchaseRequestStatus.APPROVAL_PENDING.name().equals(status)) {
            return approvalNavigationAction(request);
        }
        if (canSourcePurchaseRequest() && List.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
        ).contains(status)) {
            RelatedDocumentRef pmd = openPmdActionTarget(request, coverage);
            if (pmd != null) {
                return navigationAction(PurchaseRequestWorkspaceActionKey.OPEN_PMD, pmd);
            }
            return commandAction(
                PurchaseRequestWorkspaceActionKey.CREATE_PMD,
                canMutatePurchaseRequest(),
                false,
                canMutatePurchaseRequest() ? null : "purchase_request.action.forbidden");
        }
        return null;
    }

    private PurchaseRequestAction commandAction(
        PurchaseRequestWorkspaceActionKey key,
        boolean enabled,
        boolean confirmationRequired,
        String disabledReasonCode
    ) {
        return new PurchaseRequestAction(key.name(), enabled, disabledReasonCode, Map.of(), confirmationRequired, "COMMAND", null);
    }

    private PurchaseRequestAction navigationAction(PurchaseRequestWorkspaceActionKey key, RelatedDocumentRef target) {
        return new PurchaseRequestAction(key.name(), target != null, target == null ? "purchase_request.action.target_missing" : null, Map.of(), false, "NAVIGATION", target);
    }

    private PurchaseRequestAction approvalNavigationAction(PurchaseRequest request) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                PurchaseRequestApprovalAdapter.MODULE_KEY,
                request.getId())
            .orElse(null);
        RelatedDocumentRef target = instance == null ? null : new RelatedDocumentRef(
            "APPROVAL_INSTANCE",
            instance.getId(),
            "approval-tasks",
            instance.getCode(),
            instance.getName(),
            instance.getStatus());
        return new PurchaseRequestAction(
            PurchaseRequestWorkspaceActionKey.TRACK_APPROVAL.name(),
            instance != null,
            instance == null ? "purchase_request.action.approval_not_started" : null,
            Map.of(),
            false,
            "NAVIGATION",
            target);
    }

    private RelatedDocumentRef openRfqActionTarget(PurchaseRequest request, CoverageContext coverage) {
        List<RFQ> rfqs = coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of());
        if (rfqs.isEmpty()) {
            return null;
        }
        RFQ rfq = rfqs.get(0);
        return new RelatedDocumentRef("RFQ", rfq.getId(), "rfq", rfq.getCode(), rfq.getName(), rfq.getStatus());
    }

    private RelatedDocumentRef openPmdActionTarget(PurchaseRequest request, CoverageContext coverage) {
        ProcurementMethodDecision decision = coverage.methodDecisionByPurchaseRequestId().get(request.getId());
        if (decision == null) {
            return null;
        }
        return new RelatedDocumentRef(
            "PROCUREMENT_METHOD_DECISION",
            decision.getId(),
            "procurement-method-decisions",
            decision.getCode(),
            decision.getName(),
            decision.getStatus());
    }

    private List<SourceWarning> sourceWarnings(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        List<PurchaseOrder> purchaseOrders = coverage.purchaseOrdersByPurchaseRequestId()
            .getOrDefault(request.getId(), List.of());
        if (purchaseOrders.isEmpty()) {
            return List.of();
        }
        long allocatedItemCount = items.stream()
            .filter(item -> !coverage.allocationsByPurchaseRequestItemId().getOrDefault(item.getId(), List.of()).isEmpty())
            .count();
        if (allocatedItemCount < items.size()) {
            return List.of(new SourceWarning(
                "purchase_request.workspace.partial_purchase_order_lineage",
                "Purchase order exists for this request, but one or more lines have no authoritative source allocation.",
                request.getId()));
        }
        return List.of();
    }

    private CoverageContext coverageContext(List<PurchaseRequestItem> items) {
        return coverageContext(items, List.of());
    }

    private CoverageContext coverageContext(List<PurchaseRequestItem> items, Collection<UUID> rootPurchaseRequestIds) {
        List<UUID> purchaseRequestItemIds = items.stream().map(PurchaseRequestItem::getId).filter(Objects::nonNull).distinct().toList();
        List<UUID> purchaseRequestIds = new ArrayList<>();
        if (rootPurchaseRequestIds != null) {
            rootPurchaseRequestIds.stream()
                .filter(Objects::nonNull)
                .forEach(purchaseRequestIds::add);
        }
        items.stream()
            .map(PurchaseRequestItem::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .forEach(purchaseRequestIds::add);
        purchaseRequestIds = purchaseRequestIds.stream().distinct().toList();
        Map<UUID, DemandPlanItem> demandPlanItemsByPurchaseRequestItemId = purchaseRequestItemIds.isEmpty()
            ? Map.of()
            : demandPlanItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds)
                .stream()
                .filter(item -> item.getPurchaseRequestItemId() != null)
                .collect(Collectors.toMap(DemandPlanItem::getPurchaseRequestItemId, Function.identity(), (left, right) -> left));
        Set<UUID> demandPlanIds = demandPlanItemsByPurchaseRequestItemId.values().stream()
            .map(DemandPlanItem::getDemandPlanId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, DemandPlan> demandPlansById = demandPlanIds.isEmpty()
            ? Map.of()
            : demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), demandPlanIds)
                .stream()
                .collect(Collectors.toMap(DemandPlan::getId, Function.identity()));
        List<PurchaseOrderSourceAllocation> allocations = purchaseRequestItemIds.isEmpty()
            ? List.of()
            : sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds);
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPurchaseRequestItemId = allocations.stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseRequestItemId));
        List<ProcurementAwardOutcomeLine> awardOutcomeLines = purchaseRequestItemIds.isEmpty()
            ? List.of()
            : awardOutcomeLineRepository
                .findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds);
        Map<UUID, List<ProcurementAwardOutcomeLine>> awardOutcomeLinesByPurchaseRequestItemId = awardOutcomeLines.stream()
            .collect(Collectors.groupingBy(ProcurementAwardOutcomeLine::getPurchaseRequestItemId));
        Set<UUID> purchaseOrderItemIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseOrderItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, PurchaseOrderItem> purchaseOrderItems = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : purchaseOrderItemRepository.findWorkspaceLineageItems(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Map<UUID, List<GoodsReceiptLine>> receiptLinesByPurchaseOrderItem = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.groupingBy(GoodsReceiptLine::getPurchaseOrderItemId));
        List<UUID> purchaseOrderIds = purchaseOrderItems.values().stream()
            .map(PurchaseOrderItem::getPurchaseOrderId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, List<ServiceAcceptanceAct>> serviceActsByPurchaseOrder = purchaseOrderIds.isEmpty()
            ? Map.of()
            : serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderIds, acceptedServiceStatuses())
                .stream()
                .collect(Collectors.groupingBy(ServiceAcceptanceAct::getPurchaseOrderId));
        Map<UUID, List<WorkCompletionAct>> workActsByPurchaseOrderItem = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : workCompletionActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds), acceptedWorkStatuses())
                .stream()
                .collect(Collectors.groupingBy(WorkCompletionAct::getPurchaseOrderItemId));
        Map<UUID, BigDecimal> allocatedTotalsByPurchaseOrderItem = allocations.stream()
            .collect(Collectors.groupingBy(
                PurchaseOrderSourceAllocation::getPurchaseOrderItemId,
                Collectors.mapping(allocation -> nvl(allocation.getOrderedQuantity()), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        Map<UUID, List<RFQ>> rfqsByPurchaseRequestId = rfqsByPurchaseRequestIds(purchaseRequestIds);
        Map<UUID, ProcurementMethodDecision> methodDecisionByPurchaseRequestId = methodDecisionsByPurchaseRequestId(purchaseRequestIds);
        Map<UUID, ProcurementProcedure> procedureByDecisionId = proceduresByDecisionId(methodDecisionByPurchaseRequestId.values());
        Map<UUID, ProcurementCampaign> campaignByProcedureId = campaignsByProcedureId(procedureByDecisionId.values());
        Map<UUID, List<PurchaseOrder>> purchaseOrdersByPurchaseRequestId = purchaseRequestIds.isEmpty()
            ? Map.of()
            : purchaseOrderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequestIds)
                .stream()
                .collect(Collectors.groupingBy(PurchaseOrder::getPurchaseRequestId));
        return new CoverageContext(
            demandPlanItemsByPurchaseRequestItemId,
            demandPlansById,
            allocationsByPurchaseRequestItemId,
            awardOutcomeLinesByPurchaseRequestItemId,
            purchaseOrderItems,
            receiptLinesByPurchaseOrderItem,
            serviceActsByPurchaseOrder,
            workActsByPurchaseOrderItem,
            allocatedTotalsByPurchaseOrderItem,
            rfqsByPurchaseRequestId,
            methodDecisionByPurchaseRequestId,
            procedureByDecisionId,
            campaignByProcedureId,
            purchaseOrdersByPurchaseRequestId
        );
    }

    private Map<UUID, List<RFQ>> rfqsByPurchaseRequestIds(Collection<UUID> purchaseRequestIds) {
        if (purchaseRequestIds == null || purchaseRequestIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = purchaseRequestIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LinkedHashMap<UUID, RFQ>> grouped = new HashMap<>();
        rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse(TenantContext.currentTenant(), ids)
            .stream()
            .filter(rfq -> Objects.equals(CompanyContext.currentCompany(), rfq.getCompanyId()))
            .filter(rfq -> rfq.getPurchaseRequestId() != null)
            .forEach(rfq -> grouped
                .computeIfAbsent(rfq.getPurchaseRequestId(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(rfq.getId(), rfq));

        List<RFQPurchaseRequestSource> sources = rfqPurchaseRequestSourceRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids, "ACTIVE");
        Map<UUID, RFQ> rfqsById = sources.isEmpty()
            ? Map.of()
            : rfqRepository.findByTenantIdAndIdInAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    sources.stream().map(RFQPurchaseRequestSource::getRfqId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .filter(rfq -> Objects.equals(CompanyContext.currentCompany(), rfq.getCompanyId()))
                .collect(Collectors.toMap(RFQ::getId, Function.identity(), (left, right) -> left));
        for (RFQPurchaseRequestSource source : sources) {
            RFQ rfq = rfqsById.get(source.getRfqId());
            if (rfq == null) {
                continue;
            }
            grouped
                .computeIfAbsent(source.getPurchaseRequestId(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(rfq.getId(), rfq);
        }
        return grouped.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> new ArrayList<>(entry.getValue().values())));
    }

    private Map<UUID, ProcurementMethodDecision> methodDecisionsByPurchaseRequestId(List<UUID> purchaseRequestIds) {
        if (purchaseRequestIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProcurementMethodDecision> result = new HashMap<>();
        for (UUID purchaseRequestId : purchaseRequestIds) {
            methodDecisionRepository
                .findFirstByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestId)
                .ifPresent(decision -> result.put(purchaseRequestId, decision));
        }
        return result;
    }

    private Map<UUID, ProcurementProcedure> proceduresByDecisionId(Collection<ProcurementMethodDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProcurementProcedure> result = new HashMap<>();
        for (ProcurementMethodDecision decision : decisions) {
            procedureRepository.findByMethodDecisionIdAndIsDeletedFalse(decision.getId())
                .filter(procedure -> TenantContext.currentTenant().equals(procedure.getTenantId()))
                .filter(procedure -> CompanyContext.currentCompany().equals(procedure.getCompanyId()))
                .ifPresent(procedure -> result.put(decision.getId(), procedure));
        }
        return result;
    }

    private Map<UUID, ProcurementCampaign> campaignsByProcedureId(Collection<ProcurementProcedure> procedures) {
        if (procedures == null || procedures.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProcurementCampaign> result = new HashMap<>();
        for (ProcurementProcedure procedure : procedures) {
            if (procedure.getCampaignId() != null) {
                campaignRepository.findByIdAndIsDeletedFalse(procedure.getCampaignId())
                    .filter(campaign -> TenantContext.currentTenant().equals(campaign.getTenantId()))
                    .filter(campaign -> CompanyContext.currentCompany().equals(campaign.getCompanyId()))
                    .ifPresent(campaign -> result.put(procedure.getId(), campaign));
            }
            if (!result.containsKey(procedure.getId())) {
                campaignRepository
                    .findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByCreatedAtDesc(
                        TenantContext.currentTenant(), CompanyContext.currentCompany(), procedure.getId())
                    .ifPresent(campaign -> result.put(procedure.getId(), campaign));
            }
        }
        return result;
    }

    private ItemFacts facts(PurchaseRequestItem item, CoverageContext coverage) {
        List<PurchaseOrderSourceAllocation> allocations = coverage.allocationsByPurchaseRequestItemId()
            .getOrDefault(item.getId(), List.of());
        List<ProcurementAwardOutcomeLine> awardOutcomeLines = coverage.awardOutcomeLinesByPurchaseRequestItemId()
            .getOrDefault(item.getId(), List.of());
        BigDecimal ordered = (awardOutcomeLines.isEmpty() ? allocations.stream()
            .map(PurchaseOrderSourceAllocation::getOrderedQuantity) : awardOutcomeLines.stream()
            .map(ProcurementAwardOutcomeLine::getAwardedQuantity))
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receivedOrAccepted = BigDecimal.ZERO;
        for (PurchaseOrderSourceAllocation allocation : allocations) {
            PurchaseOrderItem poItem = coverage.purchaseOrderItems().get(allocation.getPurchaseOrderItemId());
            if (poItem == null) {
                continue;
            }
            ProcurementLineType lineType = poItem.getLineType() == null ? ProcurementLineType.MATERIAL : poItem.getLineType();
            if (lineType == ProcurementLineType.MATERIAL) {
                BigDecimal accepted = coverage.receiptLinesByPurchaseOrderItem()
                    .getOrDefault(poItem.getId(), List.of())
                    .stream()
                    .map(GoodsReceiptLine::getAcceptedQuantity)
                    .map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                receivedOrAccepted = receivedOrAccepted.add(attributedQuantity(allocation, accepted, coverage));
            } else if (lineType == ProcurementLineType.WORK) {
                BigDecimal completed = coverage.workActsByPurchaseOrderItem()
                    .getOrDefault(poItem.getId(), List.of())
                    .stream()
                    .map(WorkCompletionAct::getCompletedQuantity)
                    .map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                receivedOrAccepted = receivedOrAccepted.add(attributedQuantity(allocation, completed, coverage));
            } else {
                boolean accepted = coverage.serviceActsByPurchaseOrder()
                    .getOrDefault(poItem.getPurchaseOrderId(), List.of())
                    .stream()
                    .anyMatch(act -> poItem.getServiceOrderId() == null
                        || Objects.equals(poItem.getServiceOrderId(), act.getServiceOrderId()));
                if (accepted) {
                    receivedOrAccepted = receivedOrAccepted.add(nvl(allocation.getOrderedQuantity()));
                }
            }
        }
        BigDecimal requested = nvl(item.getQuantity());
        boolean supplied = requested.signum() <= 0 || receivedOrAccepted.compareTo(requested) >= 0;
        return new ItemFacts(ordered, receivedOrAccepted, supplied);
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
        BigDecimal share = purchaseOrderItemQuantity.multiply(allocated).divide(totalAllocated, 4, RoundingMode.HALF_UP);
        return share.min(allocated);
    }

    private PurchaseRequestCoverageStatus coverageStatus(PurchaseRequestItem item, ItemFacts facts) {
        BigDecimal requested = nvl(item.getQuantity());
        if (facts.supplied()) {
            return PurchaseRequestCoverageStatus.SUPPLIED;
        }
        if (requested.signum() > 0 && facts.receivedOrAccepted().signum() > 0) {
            return PurchaseRequestCoverageStatus.PARTIAL;
        }
        if (facts.ordered().signum() > 0) {
            return PurchaseRequestCoverageStatus.ORDERED;
        }
        return PurchaseRequestCoverageStatus.NOT_STARTED;
    }

    private PurchaseRequestProcessStage currentStage(
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        if (isFulfilled(items, coverage)) {
            return PurchaseRequestProcessStage.FULFILLED;
        }
        List<ItemFacts> facts = items.stream().map(item -> facts(item, coverage)).toList();
        if (facts.stream().anyMatch(fact -> fact.receivedOrAccepted().signum() > 0)) {
            return PurchaseRequestProcessStage.RECEIPT_OR_ACCEPTANCE;
        }
        if (facts.stream().anyMatch(fact -> fact.ordered().signum() > 0)) {
            return PurchaseRequestProcessStage.PURCHASE_ORDER;
        }
        String status = request.getStatus();
        if (List.of(
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
        ).contains(status) || hasProcurementRfq(request, coverage)) {
            return PurchaseRequestProcessStage.SOURCING;
        }
        if (PurchaseRequestStatus.APPROVAL_PENDING.name().equals(status)) {
            return PurchaseRequestProcessStage.PR_APPROVAL;
        }
        if (PurchaseRequestStatus.APPROVED.name().equals(status)) {
            return PurchaseRequestProcessStage.PROCUREMENT_METHOD;
        }
        if (List.of(PurchaseRequestStatus.PO_CREATED.name(), PurchaseRequestStatus.CONVERTED_TO_PO.name()).contains(status)) {
            return PurchaseRequestProcessStage.INBOUND_OR_EXECUTION;
        }
        if (request.getDemandPlanId() != null
            && List.of(PurchaseRequestStatus.DRAFT.name(), PurchaseRequestStatus.RETURNED.name()).contains(status)) {
            return PurchaseRequestProcessStage.PURCHASE_REQUEST;
        }
        return PurchaseRequestProcessStage.PURCHASE_REQUEST;
    }

    private DemandPlanStageState currentState(PurchaseRequest request) {
        if (PurchaseRequestStatus.CANCELLED.name().equals(request.getStatus())) {
            return DemandPlanStageState.CANCELLED;
        }
        if (PurchaseRequestStatus.REJECTED.name().equals(request.getStatus())) {
            return DemandPlanStageState.BLOCKED;
        }
        return DemandPlanStageState.IN_PROGRESS;
    }

    private boolean isFulfilled(List<PurchaseRequestItem> items, CoverageContext coverage) {
        return !items.isEmpty() && items.stream().allMatch(item -> facts(item, coverage).supplied());
    }

    private Counts counts(List<PurchaseRequestItem> items, CoverageContext coverage) {
        int ordered = 0;
        int partial = 0;
        int full = 0;
        for (PurchaseRequestItem item : items) {
            ItemFacts facts = facts(item, coverage);
            PurchaseRequestCoverageStatus status = coverageStatus(item, facts);
            if (facts.ordered().signum() > 0) {
                ordered++;
            }
            if (status == PurchaseRequestCoverageStatus.PARTIAL) {
                partial++;
            }
            if (status == PurchaseRequestCoverageStatus.SUPPLIED) {
                full++;
            }
        }
        return new Counts(items.size(), ordered, partial, full);
    }

    private List<String> supplierNames(List<PurchaseRequestItem> items, CoverageContext coverage) {
        return items.stream()
            .flatMap(item -> supplierNames(item, coverage).stream())
            .distinct()
            .sorted()
            .toList();
    }

    private List<String> supplierNames(PurchaseRequestItem item, CoverageContext coverage) {
        Set<String> names = new HashSet<>();
        for (PurchaseOrderSourceAllocation allocation : coverage.allocationsByPurchaseRequestItemId()
            .getOrDefault(item.getId(), List.of())) {
            PurchaseOrderItem poItem = coverage.purchaseOrderItems().get(allocation.getPurchaseOrderItemId());
            if (poItem == null || poItem.getPurchaseOrder() == null || poItem.getPurchaseOrder().getSupplier() == null) {
                continue;
            }
            Supplier supplier = poItem.getPurchaseOrder().getSupplier();
            names.add(firstNonBlank(supplier.getName(), supplier.getShortName(), supplier.getCode()));
        }
        return names.stream().filter(Objects::nonNull).sorted().toList();
    }

    private boolean hasProcurementRfq(PurchaseRequest request, CoverageContext coverage) {
        return coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of()).stream()
            .anyMatch(rfq -> !"PRICE_DISCOVERY".equals(rfq.getPurpose()));
    }

    private String procurementMethodSummary(PurchaseRequest request, CoverageContext coverage) {
        ProcurementMethodDecision decision = coverage.methodDecisionByPurchaseRequestId().get(request.getId());
        if (decision == null) {
            return "Procurement method decision required";
        }
        ProcurementProcedure procedure = coverage.procedureByDecisionId().get(decision.getId());
        if (procedure == null) {
            return "Method decision " + decision.getStatus();
        }
        return "Procedure " + procedure.getStatus() + " / " + procedure.getMethod();
    }

    private List<RelatedDocumentRef> procurementMethodDocuments(PurchaseRequest request, CoverageContext coverage) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        ProcurementMethodDecision decision = coverage.methodDecisionByPurchaseRequestId().get(request.getId());
        if (decision != null) {
            refs.add(methodDecisionDocumentRef(decision));
            ProcurementProcedure procedure = coverage.procedureByDecisionId().get(decision.getId());
            if (procedure != null) {
                refs.add(procedureDocumentRef(procedure, coverage));
                ProcurementCampaign campaign = coverage.campaignByProcedureId().get(procedure.getId());
                if (campaign != null) {
                    refs.add(campaignDocumentRef(campaign));
                }
            }
        }
        coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of()).stream()
            .filter(rfq -> "PRICE_DISCOVERY".equals(rfq.getPurpose()))
            .map(rfq -> documentRef("PRICE_DISCOVERY_RFQ", rfq.getId(), "rfq", rfq.getCode(), rfq.getName(), rfq.getStatus()))
            .forEach(refs::add);
        return refs;
    }

    private String stageSummary(
        PurchaseRequestProcessStage stage,
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        return switch (stage) {
            case DEMAND_PLAN -> request.getDemandPlanId() == null ? "No linked demand plan" : "Linked to demand plan";
            case PURCHASE_REQUEST -> "Purchase request preparation";
            case PR_APPROVAL -> "Approval workflow";
            case PROCUREMENT_METHOD -> procurementMethodSummary(request, coverage);
            case SOURCING -> coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of()).size() + " RFQ record(s)";
            case PURCHASE_ORDER -> counts(items, coverage).ordered() + " line(s) ordered";
            case INBOUND_OR_EXECUTION -> coverage.purchaseOrdersByPurchaseRequestId().getOrDefault(request.getId(), List.of()).size() + " purchase order(s)";
            case RECEIPT_OR_ACCEPTANCE -> counts(items, coverage).partial() + " line(s) partially fulfilled";
            case FULFILLED -> counts(items, coverage).full() + " line(s) fully fulfilled";
        };
    }

    private List<RelatedDocumentRef> documentsForStage(
        PurchaseRequestProcessStage stage,
        PurchaseRequest request,
        List<PurchaseRequestItem> items,
        CoverageContext coverage
    ) {
        return switch (stage) {
            case DEMAND_PLAN -> request.getDemandPlan() == null ? List.of() : List.of(documentRef(
                "DEMAND_PLAN",
                request.getDemandPlan().getId(),
                "demand-plans",
                request.getDemandPlan().getCode(),
                request.getDemandPlan().getName(),
                request.getDemandPlan().getStatus()));
            case PROCUREMENT_METHOD -> procurementMethodDocuments(request, coverage);
            case SOURCING -> coverage.rfqsByPurchaseRequestId().getOrDefault(request.getId(), List.of()).stream()
                .map(rfq -> documentRef("RFQ", rfq.getId(), "rfq", rfq.getCode(), rfq.getName(), rfq.getStatus()))
                .toList();
            case PURCHASE_ORDER, INBOUND_OR_EXECUTION, RECEIPT_OR_ACCEPTANCE -> coverage.purchaseOrdersByPurchaseRequestId()
                .getOrDefault(request.getId(), List.of()).stream()
                .map(po -> documentRef("PURCHASE_ORDER", po.getId(), "purchase-orders", po.getCode(), po.getName(), po.getStatus()))
                .toList();
            default -> List.of();
        };
    }

    private RelatedDocumentRef documentRef(
        String documentType,
        UUID documentId,
        String moduleKey,
        String code,
        String name,
        String status
    ) {
        return new RelatedDocumentRef(documentType, documentId, moduleKey, code, displayName(name), status);
    }

    private RelatedDocumentRef documentRef(
        String documentType,
        UUID documentId,
        String moduleKey,
        String code,
        String name,
        String status,
        List<RelatedDocumentField> fields
    ) {
        return new RelatedDocumentRef(documentType, documentId, moduleKey, code, displayName(name), status, fields);
    }

    private RelatedDocumentRef methodDecisionDocumentRef(ProcurementMethodDecision decision) {
        return documentRef(
            "PROCUREMENT_METHOD_DECISION",
            decision.getId(),
            "procurement-method-decisions",
            decision.getCode(),
            decision.getName(),
            decision.getStatus(),
            fields(
                field("method", decision.getSelectedMethod()),
                field("amount", amountValue(decision.getEstimatedAmount(), decision.getCurrencyCode())),
                field("supplierCount", decision.getPotentialSupplierCount()),
                field("commission", commissionName(decision.getCommissionId())),
                field("decision", decision.getStatus())
            ));
    }

    private RelatedDocumentRef procedureDocumentRef(ProcurementProcedure procedure, CoverageContext coverage) {
        return documentRef(
            "PROCUREMENT_PROCEDURE",
            procedure.getId(),
            "procurement-procedures",
            procedure.getCode(),
            procedure.getName(),
            procedure.getStatus(),
            fields(
                field("procedureType", procedure.getExecutionPath()),
                field("status", procedure.getStatus()),
                field("priceDiscovery", procedure.isRequiresPriceDiscovery() ? "Yes" : "No"),
                field("linkedRfq", rfqName(procedure.getRfqId(), coverage)),
                field("awardedSupplier", supplierName(procedure.getAwardedSupplierId())),
                field("awardReference", procedure.getAwardReference()),
                field("finalizedAmount", amountValue(procedure.getFinalizedAmount(), procedure.getFinalizedCurrencyCode())),
                field("dates", dateRange(dateValue(procedure.getLaunchedAt()), dateValue(procedure.getCompletedAt())))
            ));
    }

    private RelatedDocumentRef campaignDocumentRef(ProcurementCampaign campaign) {
        return documentRef(
            "PROCUREMENT_CAMPAIGN",
            campaign.getId(),
            "procurement-campaigns",
            campaign.getCode(),
            campaign.getName(),
            campaign.getStatus(),
            fields(
                field("campaignType", campaign.getCampaignType()),
                field("targetSupplierCount", campaign.getTargetSupplierCount()),
                field("plannedSpend", amountValue(campaign.getPlannedSpendAmount(), campaign.getCurrencyCode())),
                field("approvedSpend", amountValue(campaign.getApprovedSpendAmount(), campaign.getCurrencyCode())),
                field("savingsTarget", amountValue(campaign.getSavingsTargetAmount(), campaign.getCurrencyCode())),
                field("plannedDates", dateRange(dateValue(campaign.getPlannedStartDate()), dateValue(campaign.getPlannedEndDate()))),
                field("campaignDates", dateRange(dateValue(campaign.getLaunchedAt()), dateValue(campaign.getCompletedAt())))
            ));
    }

    private List<RelatedDocumentField> fields(RelatedDocumentField... fields) {
        return java.util.Arrays.stream(fields)
            .filter(Objects::nonNull)
            .filter(field -> field.value() != null && !field.value().isBlank())
            .toList();
    }

    private RelatedDocumentField field(String key, Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : new RelatedDocumentField(key, text);
    }

    private String amountValue(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            return null;
        }
        String normalized = amount.stripTrailingZeros().toPlainString();
        return firstNonBlank(currencyCode) == null ? normalized : normalized + " " + currencyCode;
    }

    private String dateValue(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private String dateValue(LocalDateTime value) {
        return value == null ? null : value.toLocalDate().toString();
    }

    private String dateRange(String start, String end) {
        if (firstNonBlank(start, end) == null) {
            return null;
        }
        return (start == null ? "—" : start) + " - " + (end == null ? "—" : end);
    }

    private String commissionName(UUID commissionId) {
        if (commissionId == null) {
            return null;
        }
        return commissionRepository.findByIdAndIsDeletedFalse(commissionId)
            .filter(commission -> TenantContext.currentTenant().equals(commission.getTenantId()))
            .filter(commission -> CompanyContext.currentCompany().equals(commission.getCompanyId()))
            .map(Commission::getName)
            .map(this::displayName)
            .orElse(commissionId.toString());
    }

    private String supplierName(UUID supplierId) {
        if (supplierId == null) {
            return null;
        }
        return supplierRepository.findByIdAndIsDeletedFalse(supplierId)
            .filter(supplier -> TenantContext.currentTenant().equals(supplier.getTenantId()))
            .filter(supplier -> CompanyContext.currentCompany().equals(supplier.getCompanyId()))
            .map(supplier -> displayName(firstNonBlank(supplier.getName(), supplier.getShortName(), supplier.getCode())))
            .orElse(supplierId.toString());
    }

    private String rfqName(UUID rfqId, CoverageContext coverage) {
        if (rfqId == null) {
            return null;
        }
        return coverage.rfqsByPurchaseRequestId().values().stream()
            .flatMap(Collection::stream)
            .filter(rfq -> rfqId.equals(rfq.getId()))
            .findFirst()
            .map(rfq -> firstNonBlank(rfq.getCode(), displayName(rfq.getName()), rfq.getId().toString()))
            .orElseGet(() -> rfqRepository.findByIdAndIsDeletedFalse(rfqId)
                .filter(rfq -> TenantContext.currentTenant().equals(rfq.getTenantId()))
                .filter(rfq -> CompanyContext.currentCompany().equals(rfq.getCompanyId()))
                .map(rfq -> firstNonBlank(rfq.getCode(), displayName(rfq.getName()), rfq.getId().toString()))
                .orElse(rfqId.toString()));
    }

    private Map<UUID, String> approvalUserNamesById(List<ApprovalTask> tasks) {
        Set<UUID> userIds = tasks.stream().map(ApprovalTask::getAssigneeUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
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
        return userNamesByUsername(usernames);
    }

    private Map<String, String> userNamesByUsername(Collection<String> usernames) {
        Set<String> normalizedUsernames = usernames.stream().map(this::blank).filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (normalizedUsernames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        normalizedUsernames.forEach(username -> userRepository
            .resolveActiveUserByUsername(TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
            .ifPresent(user -> names.put(normalizeUsername(username), userDisplayName(user))));
        return names;
    }
    private Map<String, String> fullNameByFullName(Collection<String> usernames) {
        Set<String> normalizedUsernames = usernames.stream().map(this::blank).filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (normalizedUsernames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        normalizedUsernames.forEach(username -> userRepository
                .resolveActiveUserByFullName(TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
                .ifPresent(user -> names.put(normalizeUsername(username), userDisplayName(user))));
        return names;
    }

    private String actorName(String username, Map<String, String> userNamesByUsername) {
        String normalized = blank(username);
        return normalized == null ? null : firstNonBlank(userNamesByUsername.get(normalizeUsername(normalized)), normalized);
    }

    private boolean canMutatePurchaseRequest() {
        return currentRoles().stream().anyMatch(role -> List.of(
            "ADMIN",
            "MM_MANAGER",
            "PROCUREMENT_MANAGER",
            "PROCUREMENT_SPECIALIST",
            "DEPARTMENT_HEAD"
        ).contains(role));
    }

    private boolean canSourcePurchaseRequest() {
        return currentRoles().stream().anyMatch(PurchaseRequestActionPolicy.SOURCING_ROLES::contains);
    }

    private boolean canOriginatePurchaseRequest() {
        return currentRoles().stream().anyMatch(role -> List.of(
            "ADMIN",
            "MM_MANAGER",
            "PROCUREMENT_MANAGER",
            "PROCUREMENT_SPECIALIST",
            "DEPARTMENT_HEAD",
            "DEPARTMENT_WORKER"
        ).contains(role));
    }

    private List<PurchaseRequestProcessStage> workspaceStages() {
        return List.of(
            PurchaseRequestProcessStage.DEMAND_PLAN,
            PurchaseRequestProcessStage.PURCHASE_REQUEST,
            PurchaseRequestProcessStage.PR_APPROVAL,
            PurchaseRequestProcessStage.PROCUREMENT_METHOD,
            PurchaseRequestProcessStage.SOURCING,
            PurchaseRequestProcessStage.PURCHASE_ORDER,
            PurchaseRequestProcessStage.INBOUND_OR_EXECUTION,
            PurchaseRequestProcessStage.RECEIPT_OR_ACCEPTANCE,
            PurchaseRequestProcessStage.FULFILLED
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

    private String userDisplayName(User user) {
        return firstNonBlank(user.getFullName(), user.getName(), user.getUsername());
    }

    private String displayName(String storedValue) {
        return storedValue == null ? null : LocalizedText.forCurrentLocale(storedValue);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
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

    private String commonCurrency(Set<String> currencies) {
        return currencies.size() == 1 ? currencies.iterator().next() : null;
    }

    private record MatchingRequests(
        List<PurchaseRequest> requests,
        Map<UUID, List<PurchaseRequestItem>> itemsByRequest,
        CoverageContext coverage,
        long totalElements
    ) {
    }

    private class SummaryAccumulator {
        private long count;
        private BigDecimal plannedAmount = BigDecimal.ZERO;
        private BigDecimal approvedAmount = BigDecimal.ZERO;
        private final Set<String> currencies = new HashSet<>();

        void addRequest(PurchaseRequest request) {
            count++;
            BigDecimal amount = nvl(request.getTotalAmount());
            plannedAmount = plannedAmount.add(amount);
            approvedAmount = approvedAmount.add(amount);
            String currency = blank(request.getCurrencyCode());
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

        void addItem(PurchaseRequest request, PurchaseRequestItem item, ItemFacts facts, CoverageContext coverage) {
            String itemType = item.getLineType() == null ? ProcurementLineType.MATERIAL.name() : item.getLineType().name();
            DemandPlanItem demandItem = coverage.demandPlanItemsByPurchaseRequestItemId().get(item.getId());
            UUID materialId = firstNonNull(item.getMaterialId(), demandItem == null ? null : demandItem.getMaterialId());
            UUID serviceId = ProcurementLineType.MATERIAL.name().equals(itemType)
                ? null
                : firstNonNull(item.getServiceRequestId(), materialId);
            String name = materialName(item, demandItem);
            String materialName = ProcurementLineType.MATERIAL.name().equals(itemType) ? name : null;
            String serviceName = ProcurementLineType.MATERIAL.name().equals(itemType) ? null : name;
            String unit = unitName(item, demandItem);
            SummaryItemKey key = materialId != null
                ? new SummaryItemKey(itemType, materialId, null, null, null)
                : serviceId != null
                    ? new SummaryItemKey(itemType, null, serviceId, null, null)
                    : new SummaryItemKey(itemType, null, null, serviceName, unit);
            items.computeIfAbsent(key, ignored -> new ItemSummaryAccumulator(itemType, materialId, materialName, serviceId, serviceName, unit))
                .add(request, item, facts);
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
        private String unitShortName;
        private final Set<UUID> requestIds = new HashSet<>();
        private BigDecimal requestedQuantity = BigDecimal.ZERO;
        private BigDecimal approvedQuantity = BigDecimal.ZERO;
        private BigDecimal orderedQuantity = BigDecimal.ZERO;
        private BigDecimal receivedOrAcceptedQuantity = BigDecimal.ZERO;
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

        void add(PurchaseRequest request, PurchaseRequestItem item, ItemFacts facts) {
            unitShortName = unitDisplay.shortName(item, item.getUnit(), item.getMaterial());
            requestIds.add(request.getId());
            BigDecimal quantity = nvl(item.getQuantity());
            requestedQuantity = requestedQuantity.add(quantity);
            approvedQuantity = approvedQuantity.add(quantity);
            orderedQuantity = orderedQuantity.add(nvl(facts.ordered()));
            receivedOrAcceptedQuantity = receivedOrAcceptedQuantity.add(nvl(facts.receivedOrAccepted()));
            plannedAmount = plannedAmount.add(quantity.multiply(nvl(item.getEstimatedPrice())));
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
                orderedQuantity,
                receivedOrAcceptedQuantity,
                plannedAmount,
                requestIds.size()
            ).withUnitShortName(unitShortName);
        }
    }

    private record SummaryItemKey(
        String itemType,
        UUID materialId,
        UUID serviceId,
        String serviceName,
        String unit
    ) {
    }

    private UUID firstNonNull(UUID... values) {
        for (UUID value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record Filter(
        PurchaseRequestWorkspaceView view,
        String search,
        UUID departmentId,
        UUID demandPlanId,
        List<String> status,
        PurchaseRequestProcessStage processStage,
        String requestType,
        String requestedBy,
        UUID materialId
    ) {
        static Filter empty() {
            return new Filter(PurchaseRequestWorkspaceView.ALL, null, null, null, List.of(), null, null, null, null);
        }
    }

    private record Counts(int total, int ordered, int partial, int full) {
    }

    private record CoverageContext(
        Map<UUID, DemandPlanItem> demandPlanItemsByPurchaseRequestItemId,
        Map<UUID, DemandPlan> demandPlansById,
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPurchaseRequestItemId,
        Map<UUID, List<ProcurementAwardOutcomeLine>> awardOutcomeLinesByPurchaseRequestItemId,
        Map<UUID, PurchaseOrderItem> purchaseOrderItems,
        Map<UUID, List<GoodsReceiptLine>> receiptLinesByPurchaseOrderItem,
        Map<UUID, List<ServiceAcceptanceAct>> serviceActsByPurchaseOrder,
        Map<UUID, List<WorkCompletionAct>> workActsByPurchaseOrderItem,
        Map<UUID, BigDecimal> allocatedTotalsByPurchaseOrderItem,
        Map<UUID, List<RFQ>> rfqsByPurchaseRequestId,
        Map<UUID, ProcurementMethodDecision> methodDecisionByPurchaseRequestId,
        Map<UUID, ProcurementProcedure> procedureByDecisionId,
        Map<UUID, ProcurementCampaign> campaignByProcedureId,
        Map<UUID, List<PurchaseOrder>> purchaseOrdersByPurchaseRequestId
    ) {
    }

    private record ItemFacts(BigDecimal ordered, BigDecimal receivedOrAccepted, boolean supplied) {
    }
}
