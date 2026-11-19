package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalHistory;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.DemandPlanStageState;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderCoverageStatus;
import com.enterprise.mm.enums.PurchaseOrderProcessStage;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey;
import com.enterprise.mm.enums.PurchaseOrderWorkspaceView;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.ApprovalStep;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.ApprovalTimeline;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.AvailableActions;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.FulfillmentSummary;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.ItemSummary;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.LineCoverage;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PayablesSummary;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.ProcessStage;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderAction;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderHeader;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceDetail;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceRow;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceSummary;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.RelatedDocumentRef;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.SourceAllocationLineage;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.SourceWarning;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.StatusSummaryRow;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.SummaryTotal;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.StatusTimelineItem;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.PurchaseOrderApprovalAdapter;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Optional;
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
public class PurchaseOrderWorkspaceService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final com.enterprise.mm.repository.RFQRepository lineageRfqRepository;
    private final com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository lineageRfqSourceRepository;
    private final com.enterprise.mm.repository.PurchaseRequestRepository lineagePurchaseRequestRepository;

    private final PurchaseOrderItemRepository itemRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final UserRepository userRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final ProcurementInvoiceEvidenceService procurementInvoiceEvidenceService;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderWorkspaceRow> worklist(Filter filter, Pageable pageable) {
        Filter effective = filter == null ? Filter.empty() : filter;
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        boolean resolvedFiltering = requiresResolvedFiltering(effective);
        Pageable queryPageable = (scope.hasDocumentScope() && !roleDataScopeService.hasWarehouseAccess()) || resolvedFiltering ? Pageable.unpaged() : pageable;
        Page<PurchaseOrder> page = purchaseOrderRepository.findAll(spec(effective), queryPageable);
        List<PurchaseOrder> scoped = page.getContent().stream()
            .filter(this::canAccess)
            .toList();
        Map<UUID, List<PurchaseOrderItem>> itemsByOrder = itemsByOrder(scoped);
        CoverageContext coverage = coverageContext(
            scoped,
            itemsByOrder.values().stream().flatMap(Collection::stream).toList());
        if (resolvedFiltering) {
            scoped = scoped.stream()
                .filter(order -> matchesResolvedFilter(
                    effective,
                    order,
                    itemsByOrder.getOrDefault(order.getId(), List.of()),
                    coverage))
                .toList();
        }
        List<PurchaseOrderWorkspaceRow> rows = scoped.stream()
            .map(order -> row(order, itemsByOrder.getOrDefault(order.getId(), List.of()), coverage))
            .toList();
        if ((scope.hasDocumentScope() && !roleDataScopeService.hasWarehouseAccess()) || resolvedFiltering) {
            return PageResponse.from(roleDataScopeService.page(rows, pageable));
        }
        return PageResponse.from(new PageImpl<>(rows, pageable, page.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderWorkspaceSummary summary(Filter filter) {
        MatchingOrders matching = matchingOrders(filter == null ? Filter.empty() : filter, Pageable.unpaged());
        Map<String, StatusSummaryAccumulator> byStatus = new HashMap<>();
        SummaryAccumulator total = new SummaryAccumulator();
        matching.orders().forEach(order -> {
            String status = firstNonBlank(order.getStatus(), "UNKNOWN");
            StatusSummaryAccumulator row = byStatus.computeIfAbsent(status, ignored -> new StatusSummaryAccumulator(status));
            row.addOrder(order);
            total.addOrder(order);
            matching.itemsByOrder().getOrDefault(order.getId(), List.of())
                .forEach(item -> row.addItem(order, item, facts(order, item, matching.coverage()), matching.coverage()));
        });
        List<StatusSummaryRow> rows = byStatus.values().stream()
            .sorted(Comparator.comparing(StatusSummaryAccumulator::status))
            .map(StatusSummaryAccumulator::toRow)
            .toList();
        return new PurchaseOrderWorkspaceSummary(rows, total.toTotal(withApprovalFlowCount(matching.orders())));
    }

    private MatchingOrders matchingOrders(Filter filter, Pageable pageable) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        boolean resolvedFiltering = requiresResolvedFiltering(filter);
        Pageable queryPageable = (scope.hasDocumentScope() && !roleDataScopeService.hasWarehouseAccess()) || resolvedFiltering ? Pageable.unpaged() : pageable;
        Page<PurchaseOrder> page = purchaseOrderRepository.findAll(spec(filter), queryPageable);
        List<PurchaseOrder> scoped = page.getContent().stream()
            .filter(this::canAccess)
            .toList();
        Map<UUID, List<PurchaseOrderItem>> itemsByOrder = itemsByOrder(scoped);
        CoverageContext coverage = coverageContext(
            scoped,
            itemsByOrder.values().stream().flatMap(Collection::stream).toList());
        if (resolvedFiltering) {
            scoped = scoped.stream()
                .filter(order -> matchesResolvedFilter(
                    filter,
                    order,
                    itemsByOrder.getOrDefault(order.getId(), List.of()),
                    coverage))
                .toList();
        }
        return new MatchingOrders(scoped, itemsByOrder, coverage, page.getTotalElements());
    }

    private long withApprovalFlowCount(List<PurchaseOrder> orders) {
        List<UUID> ids = orders.stream()
            .map(PurchaseOrder::getId)
            .filter(Objects::nonNull)
            .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return approvalInstanceRepository.countByTenantIdAndCompanyIdAndModuleKeyAndEntityIdInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            PurchaseOrderApprovalAdapter.MODULE_KEY,
            ids
        );
    }

    @Transactional(readOnly = true)
    public PurchaseOrderWorkspaceDetail detail(UUID id) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(CompanyContext.currentCompany(), candidate.getCompanyId()))
            .filter(this::canAccess)
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + id));
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .stream()
            .sorted(Comparator.comparing(PurchaseOrderItem::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        CoverageContext coverage = coverageContext(List.of(order), items);
        LocalDateTime generatedAt = LocalDateTime.now();
        PayablesSummary payablesSummary = payablesSummary(order);
        return new PurchaseOrderWorkspaceDetail(
            header(order),
            fulfillmentSummary(order, items, coverage, generatedAt),
            payablesSummary,
            stages(order, items, coverage, payablesSummary),
            items.stream().map(item -> lineCoverage(order, item, coverage)).toList(),
            approvalTimeline(order),
            statusHistory(order),
            relatedDocuments(order, items, coverage, payablesSummary),
            actions(order, items, coverage, payablesSummary),
            generatedAt,
            sourceWarnings(order, items, coverage, payablesSummary)
        );
    }

    private PayablesSummary payablesSummary(PurchaseOrder order) {
        ProcurementInvoiceEvidenceService.EvidenceSummary summary = procurementInvoiceEvidenceService.summarize(order);
        List<ContractInvoice> invoices = invoicesForOrder(order);
        int paymentCount = paymentsForOrder(order, invoices).size();
        return new PayablesSummary(
            summary.evidenceAvailable(),
            summary.evidenceAmount(),
            summary.invoicedAmount(),
            summary.remainingInvoiceableAmount(),
            summary.paidAmount(),
            summary.outstandingPayableAmount(),
            invoices.size(),
            paymentCount,
            summary.primarySourceDocumentType() == null ? null : summary.primarySourceDocumentType().name(),
            summary.primarySourceDocumentId()
        );
    }

    private Specification<PurchaseOrder> spec(Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("supplier", JoinType.LEFT);
                var purchaseRequest = root.fetch("purchaseRequest", JoinType.LEFT);
                purchaseRequest.fetch("demandPlan", JoinType.LEFT);
                root.fetch("contract", JoinType.LEFT);
                query.distinct(true);
            }
            String search = blank(filter.search());
            if (search != null) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                var supplier = root.join("supplier", JoinType.LEFT);
                var purchaseRequest = root.join("purchaseRequest", JoinType.LEFT);
                var demandPlan = purchaseRequest.join("demandPlan", JoinType.LEFT);
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), like),
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(supplier.get("name")), like),
                    cb.like(cb.lower(purchaseRequest.get("code")), like),
                    cb.like(cb.lower(demandPlan.get("code")), like)
                ));
            }
            List<String> statuses = statuses(filter);
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (filter.supplierId() != null) {
                predicates.add(cb.equal(root.get("supplierId"), filter.supplierId()));
            }
            if (filter.purchaseRequestId() != null) {
                predicates.add(cb.equal(root.get("purchaseRequestId"), filter.purchaseRequestId()));
            }
            if (filter.demandPlanId() != null) {
                predicates.add(cb.equal(root.join("purchaseRequest", JoinType.LEFT).get("demandPlanId"), filter.demandPlanId()));
            }
            if (filter.departmentId() != null) {
                predicates.add(cb.equal(root.join("purchaseRequest", JoinType.LEFT).get("departmentId"), filter.departmentId()));
            }
            if (blank(filter.requestType()) != null) {
                predicates.add(cb.equal(
                    root.get("requestType"),
                    com.enterprise.mm.enums.RequestType.valueOf(filter.requestType().trim().toUpperCase(Locale.ROOT))));
            }
            if (filter.view() == PurchaseOrderWorkspaceView.MY) {
                predicates.add(cb.equal(cb.lower(root.get("createdBy")), currentUsername().toLowerCase(Locale.ROOT)));
            }
            if (filter.materialId() != null && query != null) {
                var itemSubquery = query.subquery(UUID.class);
                var item = itemSubquery.from(PurchaseOrderItem.class);
                itemSubquery.select(item.get("purchaseOrderId")).where(
                    cb.equal(item.get("tenantId"), TenantContext.currentTenant()),
                    cb.equal(item.get("companyId"), CompanyContext.currentCompany()),
                    cb.isFalse(item.get("isDeleted")),
                    cb.or(
                        cb.equal(item.get("id"), filter.materialId()),
                        cb.equal(item.get("materialId"), filter.materialId()),
                        cb.equal(item.get("serviceRequestId"), filter.materialId()),
                        cb.equal(item.get("serviceOrderId"), filter.materialId())
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
            case DRAFT -> List.of(PurchaseOrderStatus.DRAFT.name(), PurchaseOrderStatus.RETURNED.name());
            case ON_APPROVAL -> List.of(PurchaseOrderStatus.APPROVAL_PENDING.name());
            case READY_TO_SEND -> List.of(PurchaseOrderStatus.APPROVED.name());
            case IN_EXECUTION -> List.of(
                PurchaseOrderStatus.SENT.name(),
                PurchaseOrderStatus.PARTIALLY_RECEIVED.name(),
                PurchaseOrderStatus.PARTIALLY_COMPLETED.name());
            case FULFILLED -> List.of();
            case ALL, MY -> List.of();
        };
    }

    private boolean requiresResolvedFiltering(Filter filter) {
        return filter.processStage() != null || filter.view() == PurchaseOrderWorkspaceView.FULFILLED;
    }

    private boolean matchesResolvedFilter(
        Filter filter,
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage
    ) {
        if (filter.processStage() != null && currentStage(order, items, coverage) != filter.processStage()) {
            return false;
        }
        if (filter.view() == PurchaseOrderWorkspaceView.FULFILLED) {
            return isFulfilled(order, items, coverage);
        }
        return true;
    }

    private Map<UUID, List<PurchaseOrderItem>> itemsByOrder(List<PurchaseOrder> orders) {
        List<UUID> ids = orders.stream().map(PurchaseOrder::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return itemRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
            .stream()
            .collect(Collectors.groupingBy(PurchaseOrderItem::getPurchaseOrderId));
    }

    private PurchaseRequest displayPurchaseRequest(PurchaseOrder order) {
        if (order.getPurchaseRequest() != null || order.getPurchaseRequestId() != null) {
            return order.getPurchaseRequest();
        }
        Contract contract = order.getContract();
        if (contract == null || contract.getSourceRfqId() == null) {
            return null;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        var rfq = lineageRfqRepository.findByIdAndIsDeletedFalse(contract.getSourceRfqId())
            .filter(value -> tenantId.equals(value.getTenantId()) && companyId.equals(value.getCompanyId()))
            .orElse(null);
        if (rfq == null) {
            return null;
        }
        Set<UUID> ids = new java.util.LinkedHashSet<>();
        if (rfq.getPurchaseRequestId() != null) {
            ids.add(rfq.getPurchaseRequestId());
        }
        lineageRfqSourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
                tenantId, companyId, rfq.getId(), "ACTIVE")
            .stream().map(com.enterprise.mm.entity.RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull).forEach(ids::add);
        // The existing header represents a single source. Never select an arbitrary source.
        if (ids.size() != 1) {
            return null;
        }
        return lineagePurchaseRequestRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                tenantId, companyId, ids).stream().findFirst().orElse(null);
    }

    private PurchaseOrderWorkspaceRow row(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage
    ) {
        Counts counts = counts(order, items, coverage);
        PurchaseRequest purchaseRequest = displayPurchaseRequest(order);
        DemandPlan demandPlan = purchaseRequest == null ? null : purchaseRequest.getDemandPlan();
        Supplier supplier = order.getSupplier();
        PurchaseOrderProcessStage processStage = currentStage(order, items, coverage);
        return new PurchaseOrderWorkspaceRow(
            order.getId(),
            order.getCode(),
            displayName(order.getName()),
            order.getRequestType() == null ? null : order.getRequestType().name(),
            order.getStatus(),
            order.getSupplierId(),
            supplier == null ? null : supplier.getCode(),
            supplier == null ? null : displayName(firstNonBlank(supplier.getName(), supplier.getShortName())),
            purchaseRequest == null ? null : purchaseRequest.getId(),
            purchaseRequest == null ? null : purchaseRequest.getCode(),
            purchaseRequest == null ? null : displayName(purchaseRequest.getName()),
            demandPlan == null ? null : demandPlan.getId(),
            demandPlan == null ? null : demandPlan.getCode(),
            demandPlan == null ? null : displayName(demandPlan.getName()),
            order.getOrderDate(),
            order.getExpectedDeliveryDate(),
            order.getCurrencyCode(),
            nvl(order.getTotalAmount()),
            processStage.name(),
            currentState(order, processStage).name(),
            counts.total(),
            counts.lineageAvailable(),
            counts.partial(),
            counts.full(),
            order.getUpdatedAt(),
            primaryAction(order, items, coverage, payablesSummary(order))
        );
    }

    private PurchaseOrderHeader header(PurchaseOrder order) {
        PurchaseRequest purchaseRequest = displayPurchaseRequest(order);
        DemandPlan demandPlan = purchaseRequest == null ? null : purchaseRequest.getDemandPlan();
        Supplier supplier = order.getSupplier();
        Contract contract = order.getContract();
        return new PurchaseOrderHeader(
            order.getId(),
            order.getCode(),
            displayName(order.getName()),
            order.getRequestType() == null ? null : order.getRequestType().name(),
            order.getStatus(),
            order.getSupplierId(),
            supplier == null ? null : supplier.getCode(),
            supplier == null ? null : displayName(firstNonBlank(supplier.getName(), supplier.getShortName())),
            purchaseRequest == null ? null : purchaseRequest.getId(),
            purchaseRequest == null ? null : purchaseRequest.getCode(),
            purchaseRequest == null ? null : displayName(purchaseRequest.getName()),
            demandPlan == null ? null : demandPlan.getId(),
            demandPlan == null ? null : demandPlan.getCode(),
            demandPlan == null ? null : displayName(demandPlan.getName()),
            order.getContractId(),
            contract == null ? null : contract.getCode(),
            contract == null ? null : displayName(contract.getName()),
            order.getOrderDate(),
            order.getExpectedDeliveryDate(),
            order.getCurrencyCode(),
            nvl(order.getTotalAmount()),
            order.getVersion()
        );
    }

    private FulfillmentSummary fulfillmentSummary(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        LocalDateTime generatedAt
    ) {
        Counts counts = counts(order, items, coverage);
        return new FulfillmentSummary(
            counts.total(),
            counts.lineageAvailable(),
            counts.partial(),
            counts.full(),
            generatedAt
        );
    }

    private List<ProcessStage> stages(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        PurchaseOrderProcessStage current = currentStage(order, items, coverage, payablesSummary);
        List<ProcessStage> result = new ArrayList<>();
        for (PurchaseOrderProcessStage stage : workspaceStages()) {
            DemandPlanStageState state;
            if ((stage == PurchaseOrderProcessStage.DEMAND_PLAN || stage == PurchaseOrderProcessStage.PURCHASE_REQUEST)
                && order.getPurchaseRequestId() == null) {
                state = DemandPlanStageState.UNSTARTED;
            } else if (stage.ordinal() < current.ordinal()) {
                state = DemandPlanStageState.COMPLETED;
            } else if (stage == current) {
                state = currentState(order, stage);
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
                stageSummary(stage, order, items, coverage, payablesSummary),
                documentsForStage(stage, order, items, coverage, payablesSummary)
            ));
        }
        return result;
    }

    private LineCoverage lineCoverage(PurchaseOrder order, PurchaseOrderItem item, CoverageContext coverage) {
        ItemFacts facts = facts(order, item, coverage);
        List<PurchaseOrderSourceAllocation> allocations = coverage.allocationsByPurchaseOrderItemId()
            .getOrDefault(item.getId(), List.of());
        return new LineCoverage(
            item.getId(),
            item.getCode(),
            displayName(item.getName()),
            item.getLineType() == null ? null : item.getLineType().name(),
            item.getMaterialId(),
            item.getMaterial() == null ? null : displayName(item.getMaterial().getName()),
            item.getUnit(),
            nvl(item.getQuantity()),
            facts.receivedOrAccepted(),
            coverageStatus(order, item, facts).name(),
            !allocations.isEmpty(),
            allocations.stream().map(allocation -> allocationLineage(allocation, coverage)).toList(),
            relatedDocuments(order, item, coverage)
        ).withUnitShortName(unitDisplay.shortName(item, item.getUnit(), item.getMaterial()));
    }

    private SourceAllocationLineage allocationLineage(
        PurchaseOrderSourceAllocation allocation,
        CoverageContext coverage
    ) {
        PurchaseRequestItem purchaseRequestItem = coverage.purchaseRequestItemsById().get(allocation.getPurchaseRequestItemId());
        DemandPlanItem demandPlanItem = purchaseRequestItem == null
            ? null
            : coverage.demandPlanItemsByPurchaseRequestItemId().get(purchaseRequestItem.getId());
        DemandPlan demandPlan = demandPlanItem == null
            ? null
            : coverage.demandPlansById().get(demandPlanItem.getDemandPlanId());
        return new SourceAllocationLineage(
            allocation.getId(),
            allocation.getPurchaseRequestItemId(),
            purchaseRequestItem == null ? null : purchaseRequestItem.getCode(),
            demandPlanItem == null ? null : demandPlanItem.getId(),
            demandPlanItem == null ? null : demandPlanItem.getCode(),
            demandPlan == null ? null : demandPlan.getId(),
            demandPlan == null ? null : demandPlan.getCode(),
            allocation.getOrderedQuantity()
        );
    }

    private ApprovalTimeline approvalTimeline(PurchaseOrder order) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                PurchaseOrderApprovalAdapter.MODULE_KEY,
                order.getId())
            .orElse(null);
        if (instance == null) {
            return new ApprovalTimeline(PurchaseOrderApprovalAdapter.MODULE_KEY, null, "NOT_STARTED", List.of());
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

    private List<StatusTimelineItem> statusHistory(PurchaseOrder order) {
        List<AuditLog> logs = auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "PurchaseOrder", order.getId());
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
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        PurchaseRequest purchaseRequest = order.getPurchaseRequest();
        if (purchaseRequest != null) {
            refs.add(documentRef(
                "PURCHASE_REQUEST",
                purchaseRequest.getId(),
                "purchase-requests",
                purchaseRequest.getCode(),
                purchaseRequest.getName(),
                purchaseRequest.getStatus()));
            if (purchaseRequest.getDemandPlan() != null) {
                DemandPlan demandPlan = purchaseRequest.getDemandPlan();
                refs.add(documentRef(
                    "DEMAND_PLAN",
                    demandPlan.getId(),
                    "demand-plans",
                    demandPlan.getCode(),
                    demandPlan.getName(),
                    demandPlan.getStatus()));
            }
        }
        if (order.getContract() != null) {
            Contract contract = order.getContract();
            refs.add(documentRef(
                "CONTRACT",
                contract.getId(),
                "contracts",
                contract.getCode(),
                contract.getName(),
                contract.getStatus()));
        }
        items.forEach(item -> relatedDocuments(order, item, coverage).forEach(ref -> {
            if (refs.stream().noneMatch(existing -> Objects.equals(existing.documentId(), ref.documentId())
                && Objects.equals(existing.documentType(), ref.documentType()))) {
                refs.add(ref);
            }
        }));
        List<ContractInvoice> invoices = invoicesForOrder(order);
        invoices
            .forEach(invoice -> {
                refs.add(documentRef(
                    "CONTRACT_INVOICE",
                    invoice.getId(),
                    "contract-invoices",
                    invoice.getInvoiceNumber(),
                    invoice.getName(),
                    invoice.getStatus()));
            });
        paymentsForOrder(order, invoices).forEach(payment -> refs.add(paymentDocumentRef(payment)));
        if (payablesSummary.primarySourceDocumentType() != null && payablesSummary.primarySourceDocumentId() != null) {
            RelatedDocumentRef evidenceRef = evidenceDocumentRef(
                payablesSummary.primarySourceDocumentType(),
                payablesSummary.primarySourceDocumentId());
            if (evidenceRef != null && refs.stream().noneMatch(existing -> Objects.equals(existing.documentId(), evidenceRef.documentId())
                && Objects.equals(existing.documentType(), evidenceRef.documentType()))) {
                refs.add(evidenceRef);
            }
        }
        return refs;
    }

    private RelatedDocumentRef evidenceDocumentRef(String sourceDocumentType, UUID sourceDocumentId) {
        return switch (sourceDocumentType) {
            case "GOODS_RECEIPT" -> documentRef(
                "GOODS_RECEIPT",
                sourceDocumentId,
                "goods-receipts",
                null,
                "Goods receipt",
                null);
            case "SERVICE_ACCEPTANCE_ACT" -> documentRef(
                "SERVICE_ACCEPTANCE_ACT",
                sourceDocumentId,
                "service-acceptance-acts",
                null,
                "Service acceptance act",
                null);
            case "WORK_COMPLETION_ACT" -> documentRef(
                "WORK_COMPLETION_ACT",
                sourceDocumentId,
                "work-completion-acts",
                null,
                "Work completion act",
                null);
            case "ASSET_ACCEPTANCE" -> documentRef(
                "ASSET_ACCEPTANCE",
                sourceDocumentId,
                "asset-acceptances",
                null,
                "Asset acceptance",
                null);
            default -> null;
        };
    }

    private List<RelatedDocumentRef> relatedDocuments(
        PurchaseOrder order,
        PurchaseOrderItem item,
        CoverageContext coverage
    ) {
        List<RelatedDocumentRef> refs = new ArrayList<>();
        for (PurchaseOrderSourceAllocation allocation : coverage.allocationsByPurchaseOrderItemId()
            .getOrDefault(item.getId(), List.of())) {
            PurchaseRequestItem purchaseRequestItem = coverage.purchaseRequestItemsById().get(allocation.getPurchaseRequestItemId());
            if (purchaseRequestItem != null) {
                refs.add(documentRef(
                    "PURCHASE_REQUEST_ITEM",
                    purchaseRequestItem.getId(),
                    "purchase-request-items",
                    purchaseRequestItem.getCode(),
                    purchaseRequestItem.getName(),
                    purchaseRequestItem.getStatus()));
            }
            DemandPlanItem demandPlanItem = purchaseRequestItem == null
                ? null
                : coverage.demandPlanItemsByPurchaseRequestItemId().get(purchaseRequestItem.getId());
            if (demandPlanItem != null) {
                refs.add(documentRef(
                    "DEMAND_PLAN_ITEM",
                    demandPlanItem.getId(),
                    "demand-plan-items",
                    demandPlanItem.getCode(),
                    demandPlanItem.getName(),
                    demandPlanItem.getStatus()));
            }
        }
        return refs;
    }

    private AvailableActions actions(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        PurchaseOrderAction primary = primaryAction(order, items, coverage, payablesSummary);
        List<PurchaseOrderAction> secondary = new ArrayList<>();
        List<PurchaseOrderAction> overflow = new ArrayList<>();
        String status = order.getStatus();
        if (PurchaseOrderStatus.DRAFT.name().equals(status) || PurchaseOrderStatus.RETURNED.name().equals(status)) {
            secondary.add(commandAction(
                PurchaseOrderWorkspaceActionKey.EDIT,
                canMutatePurchaseOrder(),
                false,
                canMutatePurchaseOrder() ? null : "purchase_order.action.forbidden"));
        }
        Optional<ContractInvoice> draftInvoice = draftInvoice(order);
        Optional<ContractInvoice> payableInvoice = payableInvoice(order, payablesSummary);
        PurchaseOrderProcessStage currentStage = currentStage(order, items, coverage, payablesSummary);
        currentStepNavigationAction(order, items, currentStage).ifPresent(secondary::add);
        if (payablesSummary.evidenceAvailable() && payablesSummary.remainingInvoiceableAmount().signum() > 0 && draftInvoice.isEmpty()) {
            secondary.add(commandAction(
                PurchaseOrderWorkspaceActionKey.CREATE_INVOICE,
                canMutatePurchaseOrder(),
                false,
                canMutatePurchaseOrder() ? null : "purchase_order.action.forbidden"));
        }
        invoiceToOpen(order, payablesSummary, draftInvoice).ifPresent(invoice -> {
            secondary.add(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_INVOICE,
                documentRef(
                    "CONTRACT_INVOICE",
                    invoice.getId(),
                    "contract-invoices",
                    invoice.getInvoiceNumber(),
                    invoice.getName(),
                    invoice.getStatus())));
        });
        Optional<ContractPayment> draftPayment = paymentByStatus(order, ContractPaymentStatus.DRAFT.name());
        Optional<ContractPayment> treasuryPayment = paymentByStatus(order, ContractPaymentStatus.SENT_TO_TREASURY.name());
        Optional<ContractPayment> openPayment = paymentToOpen(order);
        if (payableInvoice.isPresent() && openPayment.isEmpty() && currentStage != PurchaseOrderProcessStage.PAYMENT) {
            ContractInvoice invoice = payableInvoice.get();
            secondary.add(commandAction(
                PurchaseOrderWorkspaceActionKey.CREATE_PAYMENT,
                canDraftPayment(),
                false,
                canDraftPayment() ? null : "purchase_order.action.forbidden",
                invoiceDocumentRef(invoice)));
        }
        draftPayment.ifPresent(payment -> secondary.add(commandAction(
            PurchaseOrderWorkspaceActionKey.SUBMIT_PAYMENT_TO_TREASURY,
            canFinalizePayment(),
            false,
            canFinalizePayment() ? null : "purchase_order.action.forbidden",
            paymentDocumentRef(payment))));
        treasuryPayment.ifPresent(payment -> secondary.add(commandAction(
            PurchaseOrderWorkspaceActionKey.MARK_PAYMENT_PAID,
            canFinalizePayment(),
            false,
            canFinalizePayment() ? null : "purchase_order.action.forbidden",
            paymentDocumentRef(payment))));
        if (currentStage != PurchaseOrderProcessStage.PAYMENT) {
            openPayment.ifPresent(payment -> secondary.add(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_PAYMENT,
                paymentDocumentRef(payment))));
        }
        if (order.getPurchaseRequestId() != null) {
            PurchaseRequest purchaseRequest = order.getPurchaseRequest();
            secondary.add(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_PURCHASE_REQUEST,
                new RelatedDocumentRef(
                    "PURCHASE_REQUEST",
                    order.getPurchaseRequestId(),
                    "purchase-requests",
                    purchaseRequest == null ? null : purchaseRequest.getCode(),
                    purchaseRequest == null ? null : purchaseRequest.getName(),
                    purchaseRequest == null ? null : purchaseRequest.getStatus())));
            if (purchaseRequest != null && purchaseRequest.getDemandPlanId() != null) {
                DemandPlan demandPlan = purchaseRequest.getDemandPlan();
                secondary.add(navigationAction(
                    PurchaseOrderWorkspaceActionKey.OPEN_DEMAND_PLAN,
                    new RelatedDocumentRef(
                        "DEMAND_PLAN",
                        purchaseRequest.getDemandPlanId(),
                        "demand-plans",
                        demandPlan == null ? null : demandPlan.getCode(),
                        demandPlan == null ? null : demandPlan.getName(),
                        demandPlan == null ? null : demandPlan.getStatus())));
            }
        }
        if (PurchaseOrderStatus.DRAFT.name().equals(status)) {
            overflow.add(commandAction(
                PurchaseOrderWorkspaceActionKey.DELETE,
                canMutatePurchaseOrder(),
                true,
                canMutatePurchaseOrder() ? null : "purchase_order.action.forbidden"));
        }
        if (!PurchaseOrderStatus.CANCELLED.name().equals(status)) {
            overflow.add(commandAction(
                PurchaseOrderWorkspaceActionKey.CANCEL,
                canMutatePurchaseOrder(),
                true,
                canMutatePurchaseOrder() ? null : "purchase_order.action.forbidden"));
        }
        return new AvailableActions(primary, secondary, overflow);
    }

    private PurchaseOrderAction primaryAction(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        String status = order.getStatus();
        if (PurchaseOrderStatus.DRAFT.name().equals(status) || PurchaseOrderStatus.RETURNED.name().equals(status)) {
            boolean ready = !items.isEmpty();
            return commandAction(
                PurchaseOrderWorkspaceActionKey.SUBMIT,
                canMutatePurchaseOrder() && ready,
                false,
                !canMutatePurchaseOrder() ? "purchase_order.action.forbidden" : ready ? null : "purchase_order.action.no_items");
        }
        if (PurchaseOrderStatus.APPROVAL_PENDING.name().equals(status)) {
            return approvalNavigationAction(order);
        }
        if (PurchaseOrderStatus.APPROVED.name().equals(status)) {
            return commandAction(
                PurchaseOrderWorkspaceActionKey.SEND,
                canMutatePurchaseOrder(),
                false,
                canMutatePurchaseOrder() ? null : "purchase_order.action.forbidden");
        }
        PurchaseOrderProcessStage stage = currentStage(order, items, coverage, payablesSummary);
        if (stage == PurchaseOrderProcessStage.INVOICING) {
            Optional<ContractInvoice> draftInvoice = draftInvoice(order);
            if (draftInvoice.isPresent()) {
                ContractInvoice invoice = draftInvoice.get();
                return navigationAction(
                    PurchaseOrderWorkspaceActionKey.OPEN_INVOICE,
                    documentRef(
                        "CONTRACT_INVOICE",
                        invoice.getId(),
                        "contract-invoices",
                        invoice.getInvoiceNumber(),
                        invoice.getName(),
                        invoice.getStatus()));
            }
            return commandAction(
                PurchaseOrderWorkspaceActionKey.CREATE_INVOICE,
                canMutatePurchaseOrder() && payablesSummary.remainingInvoiceableAmount().signum() > 0,
                false,
                !canMutatePurchaseOrder()
                    ? "purchase_order.action.forbidden"
                    : payablesSummary.remainingInvoiceableAmount().signum() > 0 ? null : "purchase_order.action.invoice_not_required");
        }
        if (stage == PurchaseOrderProcessStage.PAYMENT) {
            Optional<ContractPayment> payment = paymentToOpen(order);
            if (payment.isPresent()) {
                return navigationAction(
                    PurchaseOrderWorkspaceActionKey.OPEN_PAYMENT,
                    paymentDocumentRef(payment.get()));
            }
            return payableInvoice(order, payablesSummary)
                .map(invoice -> commandAction(
                    PurchaseOrderWorkspaceActionKey.CREATE_PAYMENT,
                    canDraftPayment(),
                    false,
                    canDraftPayment() ? null : "purchase_order.action.forbidden",
                    invoiceDocumentRef(invoice)))
                .orElse(commandAction(
                    PurchaseOrderWorkspaceActionKey.CREATE_PAYMENT,
                    false,
                    false,
                    "purchase_order.action.invoice_missing"));
        }
        return null;
    }

    private Optional<PurchaseOrderAction> currentStepNavigationAction(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        PurchaseOrderProcessStage currentStage
    ) {
        if (currentStage != PurchaseOrderProcessStage.INBOUND_OR_EXECUTION
            && currentStage != PurchaseOrderProcessStage.RECEIPT_OR_ACCEPTANCE) {
            return Optional.empty();
        }

        boolean hasFixedAsset = items.stream().anyMatch(item ->
            item.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL &&
            com.enterprise.mm.enums.MaterialType.FIXED_ASSET == item.getMaterialTypeSnapshot());

        if (hasFixedAsset) {
            return Optional.of(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_ASSET_ACCEPTANCE,
                documentRef("PURCHASE_ORDER", order.getId(), "asset-acceptances", order.getCode(), order.getName(), order.getStatus())));
        }

        boolean hasWork = items.stream().anyMatch(item -> item.getLineType() == com.enterprise.mm.enums.ProcurementLineType.WORK);
        if (hasWork) {
            UUID serviceOrderId = items.stream()
                .filter(item -> item.getLineType() == com.enterprise.mm.enums.ProcurementLineType.WORK)
                .map(PurchaseOrderItem::getServiceOrderId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(order.getId());
            String documentType = serviceOrderId.equals(order.getId()) ? "PURCHASE_ORDER" : "SERVICE_ORDER";
            return Optional.of(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_WORK_COMPLETION_ACT,
                documentRef(documentType, serviceOrderId, "work-completion-acts", order.getCode(), order.getName(), order.getStatus())));
        }

        if (RequestType.SERVICE.equals(order.getRequestType())) {
            UUID serviceOrderId = items.stream()
                .map(PurchaseOrderItem::getServiceOrderId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(order.getId());
            String documentType = serviceOrderId.equals(order.getId()) ? "PURCHASE_ORDER" : "SERVICE_ORDER";
            return Optional.of(navigationAction(
                PurchaseOrderWorkspaceActionKey.OPEN_SERVICE_ACCEPTANCE_ACT,
                documentRef(documentType, serviceOrderId, "service-acceptance-acts", order.getCode(), order.getName(), order.getStatus())));
        }

        return Optional.of(navigationAction(
            PurchaseOrderWorkspaceActionKey.OPEN_GOODS_RECEIPT,
            documentRef("PURCHASE_ORDER", order.getId(), "goods-receipt", order.getCode(), order.getName(), order.getStatus())));
    }

    private PurchaseOrderAction commandAction(
        PurchaseOrderWorkspaceActionKey key,
        boolean enabled,
        boolean confirmationRequired,
        String disabledReasonCode
    ) {
        return commandAction(key, enabled, confirmationRequired, disabledReasonCode, null);
    }

    private PurchaseOrderAction commandAction(
        PurchaseOrderWorkspaceActionKey key,
        boolean enabled,
        boolean confirmationRequired,
        String disabledReasonCode,
        RelatedDocumentRef targetDocument
    ) {
        return new PurchaseOrderAction(key.name(), enabled, disabledReasonCode, Map.of(), confirmationRequired, "COMMAND", targetDocument);
    }

    private PurchaseOrderAction navigationAction(PurchaseOrderWorkspaceActionKey key, RelatedDocumentRef target) {
        return new PurchaseOrderAction(key.name(), target != null, target == null ? "purchase_order.action.target_missing" : null, Map.of(), false, "NAVIGATION", target);
    }

    private PurchaseOrderAction approvalNavigationAction(PurchaseOrder order) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                PurchaseOrderApprovalAdapter.MODULE_KEY,
                order.getId())
            .orElse(null);
        RelatedDocumentRef target = instance == null ? null : new RelatedDocumentRef(
            "APPROVAL_INSTANCE",
            instance.getId(),
            "approval-tasks",
            instance.getCode(),
            instance.getName(),
            instance.getStatus());
        return new PurchaseOrderAction(
            PurchaseOrderWorkspaceActionKey.TRACK_APPROVAL.name(),
            instance != null,
            instance == null ? "purchase_order.action.approval_not_started" : null,
            Map.of(),
            false,
            "NAVIGATION",
            target);
    }

    private List<SourceWarning> sourceWarnings(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        List<SourceWarning> warnings = new ArrayList<>();
        if (order.getPurchaseRequestId() != null && !items.isEmpty()) {
            List<PurchaseOrderItem> missing = items.stream()
                .filter(item -> coverage.allocationsByPurchaseOrderItemId().getOrDefault(item.getId(), List.of()).isEmpty())
                .toList();
            if (!missing.isEmpty()) {
                warnings.add(new SourceWarning(
                    "purchase_order.workspace.missing_source_allocation",
                    "Purchase order is linked to a purchase request, but one or more lines have no authoritative source allocation.",
                    order.getId()));
            }
        }
        if (payablesSummary.evidenceAvailable() && payablesSummary.invoicedAmount().signum() == 0 && draftInvoice(order).isEmpty()) {
            warnings.add(new SourceWarning(
                "purchase_order.workspace.invoice_required",
                "Fulfillment evidence is available, but no contract invoice has been created yet.",
                order.getId()));
        }
        return warnings;
    }

    private Optional<ContractInvoice> invoiceToOpen(
        PurchaseOrder order,
        PayablesSummary payablesSummary,
        Optional<ContractInvoice> draftInvoice
    ) {
        if (draftInvoice.isPresent()) {
            return draftInvoice;
        }
        return payableInvoice(order, payablesSummary);
    }

    private Optional<ContractInvoice> draftInvoice(PurchaseOrder order) {
        return contractInvoiceRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                order.getCompanyId(),
                order.getId(),
                ContractInvoiceStatus.DRAFT.name()
            );
    }

    private Optional<ContractInvoice> payableInvoice(PurchaseOrder order, PayablesSummary payablesSummary) {
        if (payablesSummary.outstandingPayableAmount().signum() <= 0) {
            return Optional.empty();
        }
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), order.getId())
            .stream()
            .filter(invoice -> List.of(
                ContractInvoiceStatus.ISSUED.name(),
                ContractInvoiceStatus.PARTIALLY_PAID.name()).contains(invoice.getStatus()))
            .findFirst();
    }

    private List<ContractInvoice> invoicesForOrder(PurchaseOrder order) {
        return contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), order.getId());
    }

    private List<ContractPayment> paymentsForOrder(PurchaseOrder order) {
        return paymentsForOrder(order, invoicesForOrder(order));
    }

    private List<ContractPayment> paymentsForOrder(PurchaseOrder order, List<ContractInvoice> invoices) {
        Map<UUID, ContractPayment> byId = new java.util.LinkedHashMap<>();
        contractPaymentRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), order.getId())
            .forEach(payment -> byId.put(payment.getId(), payment));
        invoices.forEach(invoice -> contractPaymentRepository
            .findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), invoice.getId())
            .forEach(payment -> byId.putIfAbsent(payment.getId(), payment)));
        return new ArrayList<>(byId.values());
    }

    private Optional<ContractPayment> paymentByStatus(PurchaseOrder order, String status) {
        return paymentsForOrder(order).stream()
            .filter(payment -> status.equals(normalizedStatus(payment.getStatus())))
            .findFirst();
    }

    private Optional<ContractPayment> paymentToOpen(PurchaseOrder order) {
        List<ContractPayment> payments = paymentsForOrder(order);
        for (String status : List.of(
            ContractPaymentStatus.DRAFT.name(),
            ContractPaymentStatus.SENT_TO_TREASURY.name(),
            ContractPaymentStatus.PAID.name())) {
            Optional<ContractPayment> match = payments.stream()
                .filter(payment -> status.equals(normalizedStatus(payment.getStatus())))
                .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return payments.stream().findFirst();
    }

    private RelatedDocumentRef invoiceDocumentRef(ContractInvoice invoice) {
        return documentRef(
            "CONTRACT_INVOICE",
            invoice.getId(),
            "contract-invoices",
            invoice.getInvoiceNumber(),
            invoice.getName(),
            invoice.getStatus());
    }

    private RelatedDocumentRef paymentDocumentRef(ContractPayment payment) {
        return documentRef(
            "CONTRACT_PAYMENT",
            payment.getId(),
            "contract-payments",
            payment.getCode(),
            payment.getName(),
            payment.getStatus());
    }

    private CoverageContext coverageContext(List<PurchaseOrder> orders, List<PurchaseOrderItem> items) {
        List<UUID> purchaseOrderItemIds = items.stream().map(PurchaseOrderItem::getId).filter(Objects::nonNull).distinct().toList();
        List<PurchaseOrderSourceAllocation> allocations = purchaseOrderItemIds.isEmpty()
            ? List.of()
            : sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds));
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPurchaseOrderItemId = allocations.stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseOrderItemId));
        Set<UUID> purchaseRequestItemIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, PurchaseRequestItem> purchaseRequestItemsById = purchaseRequestItemIds.isEmpty()
            ? Map.of()
            : purchaseRequestItemRepository.findWorkspaceLineageItems(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemIds)
                .stream()
                .collect(Collectors.toMap(PurchaseRequestItem::getId, Function.identity()));
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
        for (PurchaseOrder order : orders) {
            if (order.getPurchaseRequest() != null && order.getPurchaseRequest().getDemandPlanId() != null) {
                demandPlanIds.add(order.getPurchaseRequest().getDemandPlanId());
            }
        }
        Map<UUID, DemandPlan> demandPlansById = demandPlanIds.isEmpty()
            ? Map.of()
            : demandPlanRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), demandPlanIds)
                .stream()
                .collect(Collectors.toMap(DemandPlan::getId, Function.identity()));
        Map<UUID, PurchaseOrderItem> purchaseOrderItems = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : itemRepository.findWorkspaceLineageItems(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
        Map<UUID, List<GoodsReceiptLine>> receiptLinesByPurchaseOrderItem = purchaseOrderItemIds.isEmpty()
            ? Map.of()
            : goodsReceiptLineRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), new ArrayList<>(purchaseOrderItemIds))
                .stream()
                .collect(Collectors.groupingBy(GoodsReceiptLine::getPurchaseOrderItemId));
        List<UUID> purchaseOrderIds = orders.stream().map(PurchaseOrder::getId).filter(Objects::nonNull).distinct().toList();
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
        return new CoverageContext(
            allocationsByPurchaseOrderItemId,
            purchaseRequestItemsById,
            demandPlanItemsByPurchaseRequestItemId,
            demandPlansById,
            purchaseOrderItems,
            receiptLinesByPurchaseOrderItem,
            serviceActsByPurchaseOrder,
            workActsByPurchaseOrderItem,
            allocatedTotalsByPurchaseOrderItem
        );
    }

    private ItemFacts facts(PurchaseOrder order, PurchaseOrderItem item, CoverageContext coverage) {
        List<PurchaseOrderSourceAllocation> allocations = coverage.allocationsByPurchaseOrderItemId()
            .getOrDefault(item.getId(), List.of());
        boolean missingLineage = order.getPurchaseRequestId() != null && allocations.isEmpty();
        BigDecimal receivedOrAccepted;
        if (allocations.isEmpty()) {
            receivedOrAccepted = directReceivedQuantity(item, coverage);
        } else {
            receivedOrAccepted = BigDecimal.ZERO;
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
        }
        BigDecimal requested = nvl(item.getQuantity());
        boolean supplied = !missingLineage && (requested.signum() <= 0 || receivedOrAccepted.compareTo(requested) >= 0);
        return new ItemFacts(missingLineage, receivedOrAccepted, supplied);
    }

    private BigDecimal directReceivedQuantity(PurchaseOrderItem item, CoverageContext coverage) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        if (lineType == ProcurementLineType.MATERIAL) {
            return coverage.receiptLinesByPurchaseOrderItem()
                .getOrDefault(item.getId(), List.of())
                .stream()
                .map(GoodsReceiptLine::getAcceptedQuantity)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        if (lineType == ProcurementLineType.WORK) {
            return coverage.workActsByPurchaseOrderItem()
                .getOrDefault(item.getId(), List.of())
                .stream()
                .map(WorkCompletionAct::getCompletedQuantity)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        boolean accepted = coverage.serviceActsByPurchaseOrder()
            .getOrDefault(item.getPurchaseOrderId(), List.of())
            .stream()
            .anyMatch(act -> item.getServiceOrderId() == null || Objects.equals(item.getServiceOrderId(), act.getServiceOrderId()));
        return accepted ? nvl(item.getQuantity()) : BigDecimal.ZERO;
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

    private PurchaseOrderCoverageStatus coverageStatus(PurchaseOrder order, PurchaseOrderItem item, ItemFacts facts) {
        if (facts.missingLineage()) {
            return PurchaseOrderCoverageStatus.UNAVAILABLE;
        }
        if (facts.supplied()) {
            return PurchaseOrderCoverageStatus.SUPPLIED;
        }
        BigDecimal requested = nvl(item.getQuantity());
        if (requested.signum() > 0 && facts.receivedOrAccepted().signum() > 0) {
            return PurchaseOrderCoverageStatus.PARTIAL;
        }
        if (requested.signum() > 0) {
            return PurchaseOrderCoverageStatus.ORDERED;
        }
        return PurchaseOrderCoverageStatus.NOT_STARTED;
    }

    private PurchaseOrderProcessStage currentStage(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage
    ) {
        return currentStage(order, items, coverage, payablesSummary(order));
    }

    private PurchaseOrderProcessStage currentStage(
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payables
    ) {
        boolean fulfilled = isFulfilled(order, items, coverage);
        if (payables.outstandingPayableAmount().signum() > 0) {
            return PurchaseOrderProcessStage.PAYMENT;
        }
        if (payables.evidenceAvailable()
            && (payables.remainingInvoiceableAmount().signum() > 0 || payables.invoicedAmount().signum() == 0)) {
            return PurchaseOrderProcessStage.INVOICING;
        }
        if (fulfilled && payables.invoicedAmount().signum() == 0 && payables.paidAmount().signum() == 0) {
            return PurchaseOrderProcessStage.INVOICING;
        }
        if (fulfilled) {
            return PurchaseOrderProcessStage.FULFILLED;
        }
        List<ItemFacts> facts = items.stream().map(item -> facts(order, item, coverage)).toList();
        if (facts.stream().anyMatch(fact -> !fact.missingLineage() && fact.receivedOrAccepted().signum() > 0)) {
            return PurchaseOrderProcessStage.RECEIPT_OR_ACCEPTANCE;
        }
        String status = order.getStatus();
        if (List.of(
            PurchaseOrderStatus.SENT.name(),
            PurchaseOrderStatus.PARTIALLY_RECEIVED.name(),
            PurchaseOrderStatus.PARTIALLY_COMPLETED.name()
        ).contains(status)) {
            return PurchaseOrderProcessStage.INBOUND_OR_EXECUTION;
        }
        if (PurchaseOrderStatus.APPROVED.name().equals(status)) {
            return PurchaseOrderProcessStage.SUPPLIER_DISPATCH;
        }
        if (PurchaseOrderStatus.APPROVAL_PENDING.name().equals(status)) {
            return PurchaseOrderProcessStage.PO_APPROVAL;
        }
        if (List.of(PurchaseOrderStatus.DRAFT.name(), PurchaseOrderStatus.RETURNED.name()).contains(status)) {
            return PurchaseOrderProcessStage.PURCHASE_ORDER;
        }
        if (order.getPurchaseRequestId() != null) {
            return PurchaseOrderProcessStage.PURCHASE_REQUEST;
        }
        if (order.getPurchaseRequest() != null && order.getPurchaseRequest().getDemandPlanId() != null) {
            return PurchaseOrderProcessStage.DEMAND_PLAN;
        }
        return PurchaseOrderProcessStage.PURCHASE_ORDER;
    }

    private DemandPlanStageState currentState(PurchaseOrder order, PurchaseOrderProcessStage stage) {
        if (PurchaseOrderStatus.CANCELLED.name().equals(order.getStatus())) {
            return DemandPlanStageState.CANCELLED;
        }
        if (PurchaseOrderStatus.REJECTED.name().equals(order.getStatus())) {
            return DemandPlanStageState.BLOCKED;
        }
        if (stage == PurchaseOrderProcessStage.INVOICING || stage == PurchaseOrderProcessStage.PAYMENT) {
            return DemandPlanStageState.IN_PROGRESS;
        }
        if (List.of(PurchaseOrderStatus.RECEIVED.name(), PurchaseOrderStatus.COMPLETED.name(), PurchaseOrderStatus.CLOSED.name())
            .contains(order.getStatus())) {
            return DemandPlanStageState.COMPLETED;
        }
        return DemandPlanStageState.IN_PROGRESS;
    }

    private boolean isFulfilled(PurchaseOrder order, List<PurchaseOrderItem> items, CoverageContext coverage) {
        return !items.isEmpty() && items.stream().allMatch(item -> {
            ItemFacts facts = facts(order, item, coverage);
            return !facts.missingLineage() && facts.supplied();
        });
    }

    private Counts counts(PurchaseOrder order, List<PurchaseOrderItem> items, CoverageContext coverage) {
        int lineageAvailable = 0;
        int partial = 0;
        int full = 0;
        for (PurchaseOrderItem item : items) {
            ItemFacts facts = facts(order, item, coverage);
            PurchaseOrderCoverageStatus status = coverageStatus(order, item, facts);
            if (!facts.missingLineage()) {
                lineageAvailable++;
            }
            if (status == PurchaseOrderCoverageStatus.PARTIAL) {
                partial++;
            }
            if (status == PurchaseOrderCoverageStatus.SUPPLIED) {
                full++;
            }
        }
        return new Counts(items.size(), lineageAvailable, partial, full);
    }

    private String stageSummary(
        PurchaseOrderProcessStage stage,
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        return switch (stage) {
            case DEMAND_PLAN -> order.getPurchaseRequest() == null || order.getPurchaseRequest().getDemandPlanId() == null
                ? "No linked demand plan" : "Linked demand plan";
            case PURCHASE_REQUEST -> order.getPurchaseRequestId() == null ? "Standalone purchase order" : "Linked purchase request";
            case PURCHASE_ORDER -> "Purchase order preparation";
            case PO_APPROVAL -> "Approval workflow";
            case SUPPLIER_DISPATCH -> "Ready to send to supplier";
            case INBOUND_OR_EXECUTION -> order.getStatus();
            case RECEIPT_OR_ACCEPTANCE -> counts(order, items, coverage).partial() + " line(s) partially fulfilled";
            case INVOICING -> payablesSummary.evidenceAvailable()
                ? payablesSummary.invoicedAmount() + " invoiced, " + payablesSummary.remainingInvoiceableAmount() + " remaining"
                : "Awaiting fulfillment evidence";
            case PAYMENT -> payablesSummary.paidAmount() + " paid, " + payablesSummary.outstandingPayableAmount() + " outstanding";
            case FULFILLED -> counts(order, items, coverage).full() + " line(s) fully fulfilled";
        };
    }

    private List<RelatedDocumentRef> documentsForStage(
        PurchaseOrderProcessStage stage,
        PurchaseOrder order,
        List<PurchaseOrderItem> items,
        CoverageContext coverage,
        PayablesSummary payablesSummary
    ) {
        return switch (stage) {
            case DEMAND_PLAN -> order.getPurchaseRequest() != null && order.getPurchaseRequest().getDemandPlan() != null
                ? List.of(documentRef(
                    "DEMAND_PLAN",
                    order.getPurchaseRequest().getDemandPlan().getId(),
                    "demand-plans",
                    order.getPurchaseRequest().getDemandPlan().getCode(),
                    order.getPurchaseRequest().getDemandPlan().getName(),
                    order.getPurchaseRequest().getDemandPlan().getStatus()))
                : List.of();
            case PURCHASE_REQUEST -> order.getPurchaseRequest() == null ? List.of() : List.of(documentRef(
                "PURCHASE_REQUEST",
                order.getPurchaseRequest().getId(),
                "purchase-requests",
                order.getPurchaseRequest().getCode(),
                order.getPurchaseRequest().getName(),
                order.getPurchaseRequest().getStatus()));
            case INVOICING -> invoicesForOrder(order)
                .stream()
                .map(this::invoiceDocumentRef)
                .toList();
            case PAYMENT -> paymentsForOrder(order)
                .stream()
                .map(this::paymentDocumentRef)
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

    private boolean canAccess(PurchaseOrder order) {
        PurchaseRequest purchaseRequest = order.getPurchaseRequest();
        return roleDataScopeService.hasWarehouseAccess() || roleDataScopeService.canAccessDocument(
            order.getCreatedBy(),
            purchaseRequest == null ? null : purchaseRequest.getRequestedBy(),
            purchaseRequest == null ? null : purchaseRequest.getDepartmentId());
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

    private boolean canMutatePurchaseOrder() {
        return currentRoles().stream().anyMatch(role -> List.of(
            "ADMIN",
            "MM_MANAGER",
            "PROCUREMENT_MANAGER",
            "PROCUREMENT_SPECIALIST"
        ).contains(role));
    }

    private boolean canDraftPayment() {
        return currentRoles().stream().anyMatch(role -> List.of(
            "ADMIN",
            "FINANCE_CONTROLLER",
            "ACCOUNTANT"
        ).contains(role));
    }

    private boolean canFinalizePayment() {
        return currentRoles().stream().anyMatch(role -> List.of(
            "ADMIN",
            "FINANCE_CONTROLLER"
        ).contains(role));
    }

    private List<PurchaseOrderProcessStage> workspaceStages() {
        return List.of(
            PurchaseOrderProcessStage.DEMAND_PLAN,
            PurchaseOrderProcessStage.PURCHASE_REQUEST,
            PurchaseOrderProcessStage.PURCHASE_ORDER,
            PurchaseOrderProcessStage.PO_APPROVAL,
            PurchaseOrderProcessStage.SUPPLIER_DISPATCH,
            PurchaseOrderProcessStage.INBOUND_OR_EXECUTION,
            PurchaseOrderProcessStage.RECEIPT_OR_ACCEPTANCE,
            PurchaseOrderProcessStage.INVOICING,
            PurchaseOrderProcessStage.PAYMENT,
            PurchaseOrderProcessStage.FULFILLED
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

    private String normalizedStatus(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

    private record MatchingOrders(
        List<PurchaseOrder> orders,
        Map<UUID, List<PurchaseOrderItem>> itemsByOrder,
        CoverageContext coverage,
        long totalElements
    ) {
    }

    private class SummaryAccumulator {
        private long count;
        private BigDecimal plannedAmount = BigDecimal.ZERO;
        private BigDecimal approvedAmount = BigDecimal.ZERO;
        private final Set<String> currencies = new HashSet<>();

        void addOrder(PurchaseOrder order) {
            count++;
            BigDecimal amount = nvl(order.getTotalAmount());
            plannedAmount = plannedAmount.add(amount);
            approvedAmount = approvedAmount.add(amount);
            String currency = blank(order.getCurrencyCode());
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

        void addItem(PurchaseOrder order, PurchaseOrderItem item, ItemFacts facts, CoverageContext coverage) {
            String itemType = summaryItemType(item);
            UUID materialId = item.getMaterialId();
            UUID serviceId = ProcurementLineType.MATERIAL.name().equals(itemType)
                ? null
                : firstNonNull(item.getServiceRequestId(), item.getServiceOrderId(), materialId, item.getId());
            String name = summaryItemName(item, itemType);
            String materialName = ProcurementLineType.MATERIAL.name().equals(itemType) ? name : null;
            String serviceName = ProcurementLineType.MATERIAL.name().equals(itemType) ? null : name;
            String unit = summaryItemUnit(item);
            SummaryItemKey key = materialId != null
                ? new SummaryItemKey(itemType, materialId, null, null, null)
                : serviceId != null
                    ? new SummaryItemKey(itemType, null, serviceId, null, null)
                    : new SummaryItemKey(itemType, null, null, serviceName, unit);
            items.computeIfAbsent(key, ignored -> new ItemSummaryAccumulator(itemType, materialId, materialName, serviceId, serviceName, unit))
                .add(order, item, facts, coverage);
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
        private final Set<UUID> orderIds = new HashSet<>();
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

        void add(PurchaseOrder order, PurchaseOrderItem item, ItemFacts facts, CoverageContext coverage) {
            unitShortName = unitDisplay.shortName(item, item.getUnit(), item.getMaterial());
            orderIds.add(order.getId());
            BigDecimal quantity = nvl(item.getQuantity());
            BigDecimal requested = requestedQuantity(item, coverage);
            requestedQuantity = requestedQuantity.add(requested);
            approvedQuantity = approvedQuantity.add(requested);
            orderedQuantity = orderedQuantity.add(quantity);
            receivedOrAcceptedQuantity = receivedOrAcceptedQuantity.add(nvl(facts.receivedOrAccepted()));
            plannedAmount = plannedAmount.add(quantity.multiply(nvl(item.getUnitPrice())));
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
                orderIds.size()
            ).withUnitShortName(unitShortName);
        }
    }

    private String summaryItemName(PurchaseOrderItem item, String itemType) {
        if (ProcurementLineType.MATERIAL.name().equals(itemType) && item.getMaterial() != null) {
            return displayName(firstNonBlank(item.getMaterial().getName(), item.getMaterial().getCode(), item.getMaterial().getArticle()));
        }
        return displayName(firstNonBlank(item.getServiceDescription(), item.getServiceCategory(), item.getName()));
    }

    private String summaryItemType(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.WORK ? ProcurementLineType.SERVICE.name() : lineType.name();
    }

    private String summaryItemUnit(PurchaseOrderItem item) {
        if (ProcurementLineType.MATERIAL.name().equals(summaryItemType(item))
            && item.getMaterial() != null && item.getMaterial().getUnitOfMeasure() != null) {
            String shortName = blank(item.getMaterial().getUnitOfMeasure().getMeasurementNameShort());
            if (shortName != null) {
                return shortName;
            }
        }
        String explicitUnit = blank(item.getUnit());
        if (explicitUnit != null) {
            return explicitUnit;
        }
        return item.getMaterial() == null ? null : firstNonBlank(
            item.getMaterial().getUnitOfMeasure() == null ? null : item.getMaterial().getUnitOfMeasure().getMeasurementNameShort(),
            item.getMaterial().getUnitOfMeasure() == null ? null : item.getMaterial().getUnitOfMeasure().getCode(),
            item.getMaterial().getUnitOfMeasure() == null ? null : item.getMaterial().getUnitOfMeasure().getMeasurementNameLong(),
            item.getMaterial().getUnitOfMeasure() == null ? null : item.getMaterial().getUnitOfMeasure().getName());
    }

    private BigDecimal requestedQuantity(PurchaseOrderItem item, CoverageContext coverage) {
        List<PurchaseOrderSourceAllocation> allocations = coverage.allocationsByPurchaseOrderItemId().getOrDefault(item.getId(), List.of());
        if (allocations.isEmpty()) {
            return nvl(item.getQuantity());
        }
        return allocations.stream()
            .map(allocation -> {
                PurchaseRequestItem requestItem = coverage.purchaseRequestItemsById().get(allocation.getPurchaseRequestItemId());
                return requestItem == null ? allocation.getOrderedQuantity() : requestItem.getQuantity();
            })
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private UUID firstNonNull(UUID... values) {
        for (UUID value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record SummaryItemKey(
        String itemType,
        UUID materialId,
        UUID serviceId,
        String serviceName,
        String unit
    ) {
    }

    public record Filter(
        PurchaseOrderWorkspaceView view,
        String search,
        UUID departmentId,
        UUID demandPlanId,
        UUID supplierId,
        UUID purchaseRequestId,
        List<String> status,
        PurchaseOrderProcessStage processStage,
        String requestType,
        UUID materialId
    ) {
        static Filter empty() {
            return new Filter(PurchaseOrderWorkspaceView.ALL, null, null, null, null, null, List.of(), null, null, null);
        }
    }

    private record Counts(int total, int lineageAvailable, int partial, int full) {
    }

    private record CoverageContext(
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByPurchaseOrderItemId,
        Map<UUID, PurchaseRequestItem> purchaseRequestItemsById,
        Map<UUID, DemandPlanItem> demandPlanItemsByPurchaseRequestItemId,
        Map<UUID, DemandPlan> demandPlansById,
        Map<UUID, PurchaseOrderItem> purchaseOrderItems,
        Map<UUID, List<GoodsReceiptLine>> receiptLinesByPurchaseOrderItem,
        Map<UUID, List<ServiceAcceptanceAct>> serviceActsByPurchaseOrder,
        Map<UUID, List<WorkCompletionAct>> workActsByPurchaseOrderItem,
        Map<UUID, BigDecimal> allocatedTotalsByPurchaseOrderItem
    ) {
    }

    private record ItemFacts(boolean missingLineage, BigDecimal receivedOrAccepted, boolean supplied) {
    }
}
