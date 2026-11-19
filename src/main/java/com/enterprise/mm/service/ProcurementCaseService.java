package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementCaseDtos.DocumentRef;
import com.enterprise.mm.payload.ProcurementCaseDtos.ProcurementCaseResponse;
import com.enterprise.mm.payload.ProcurementCaseDtos.SourceWarning;
import com.enterprise.mm.payload.ProcurementCaseDtos.StageRef;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcurementCaseService {
    private static final List<String> STAGE_ORDER = List.of(
        "DEMAND_PLAN",
        "PLAN_APPROVAL",
        "PURCHASE_REQUEST",
        "PR_APPROVAL",
        "PROCUREMENT_METHOD",
        "SOURCING",
        "AWARD",
        "PURCHASE_ORDER",
        "PO_APPROVAL",
        "SUPPLIER_DISPATCH",
        "RECEIPT_OR_ACCEPTANCE",
        "INVOICING",
        "PAYMENT",
        "FULFILLED"
    );

    private final DemandPlanRepository demandPlanRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    private final RFQRepository rfqRepository;
    private final RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository;
    private final ProcurementMethodDecisionRepository methodDecisionRepository;
    private final ProcurementProcedureRepository procedureRepository;

    public ProcurementCaseService(
        DemandPlanRepository demandPlanRepository,
        PurchaseRequestRepository purchaseRequestRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository,
        RFQRepository rfqRepository,
        RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository,
        ProcurementMethodDecisionRepository methodDecisionRepository,
        ProcurementProcedureRepository procedureRepository
    ) {
        this.demandPlanRepository = demandPlanRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.purchaseOrderSourceAllocationRepository = purchaseOrderSourceAllocationRepository;
        this.rfqRepository = rfqRepository;
        this.rfqPurchaseRequestSourceRepository = rfqPurchaseRequestSourceRepository;
        this.methodDecisionRepository = methodDecisionRepository;
        this.procedureRepository = procedureRepository;
    }

    @Transactional(readOnly = true)
    public ProcurementCaseResponse get(String rootType, UUID id) {
        RootKind rootKind = parseRoot(rootType);
        CaseContext context = switch (rootKind) {
            case DEMAND_PLAN -> fromDemandPlan(id);
            case PURCHASE_REQUEST -> fromPurchaseRequest(id);
            case PURCHASE_ORDER -> fromPurchaseOrder(id);
        };
        String currentStage = currentStage(context);
        List<SourceWarning> warnings = sourceWarnings(context.purchaseOrders());
        return new ProcurementCaseResponse(
            rootKind.name(),
            id,
            rootRef(rootKind, id, context),
            currentStage,
            warnings.isEmpty() ? "AUTHORITATIVE" : "PARTIAL",
            stages(context, currentStage),
            relatedDocuments(context),
            warnings,
            LocalDateTime.now()
        );
    }

    private CaseContext fromDemandPlan(UUID id) {
        DemandPlan demandPlan = demandPlanRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("DemandPlan not found: " + id));
        List<PurchaseRequest> requests = purchaseRequestRepository
            .findByTenantIdAndCompanyIdAndDemandPlanIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), demandPlan.getId());
        return assemble(demandPlan, requests, List.of());
    }

    private CaseContext fromPurchaseRequest(UUID id) {
        PurchaseRequest request = purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("PurchaseRequest not found: " + id));
        DemandPlan demandPlan = request.getDemandPlanId() == null ? null : demandPlanRepository.findByIdAndIsDeletedFalse(request.getDemandPlanId())
            .filter(this::scoped)
            .orElse(null);
        return assemble(demandPlan, List.of(request), List.of());
    }

    private CaseContext fromPurchaseOrder(UUID id) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + id));
        PurchaseRequest request = order.getPurchaseRequestId() == null ? null : purchaseRequestRepository.findByIdAndIsDeletedFalse(order.getPurchaseRequestId())
            .filter(this::scoped)
            .orElse(null);
        DemandPlan demandPlan = request == null || request.getDemandPlanId() == null ? null : demandPlanRepository.findByIdAndIsDeletedFalse(request.getDemandPlanId())
            .filter(this::scoped)
            .orElse(null);
        return assemble(demandPlan, request == null ? List.of() : List.of(request), List.of(order));
    }

    private CaseContext assemble(DemandPlan demandPlan, List<PurchaseRequest> requests, List<PurchaseOrder> seedOrders) {
        List<UUID> requestIds = requests.stream().map(PurchaseRequest::getId).filter(Objects::nonNull).distinct().toList();
        List<PurchaseOrder> orders = new ArrayList<>(seedOrders);
        if (!requestIds.isEmpty()) {
            purchaseOrderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), requestIds)
                .stream()
                .filter(this::scoped)
                .forEach(orders::add);
        }
        orders = orders.stream()
            .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity(), (first, second) -> first, LinkedHashMap::new))
            .values()
            .stream()
            .toList();
        Map<UUID, List<RFQ>> rfqsByRequest = rfqsByPurchaseRequestIds(requestIds);
        List<RFQ> rfqs = rfqsByRequest.values().stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toMap(RFQ::getId, Function.identity(), (first, second) -> first, LinkedHashMap::new))
            .values()
            .stream()
            .toList();
        List<ProcurementMethodDecision> decisions = requestIds.stream()
            .map(requestId -> methodDecisionRepository
                .findFirstByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), requestId)
                .orElse(null))
            .filter(Objects::nonNull)
            .toList();
        List<ProcurementProcedure> procedures = decisions.stream()
            .map(decision -> procedureRepository.findByMethodDecisionIdAndIsDeletedFalse(decision.getId())
                .filter(this::scoped)
                .orElse(null))
            .filter(Objects::nonNull)
            .toList();
        return new CaseContext(demandPlan, requests, decisions, procedures, rfqs, orders);
    }

    private Map<UUID, List<RFQ>> rfqsByPurchaseRequestIds(Collection<UUID> purchaseRequestIds) {
        if (purchaseRequestIds == null || purchaseRequestIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = purchaseRequestIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, LinkedHashMap<UUID, RFQ>> grouped = new HashMap<>();
        rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse(TenantContext.currentTenant(), ids)
            .stream()
            .filter(this::scoped)
            .filter(rfq -> rfq.getPurchaseRequestId() != null)
            .forEach(rfq -> grouped
                .computeIfAbsent(rfq.getPurchaseRequestId(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(rfq.getId(), rfq));
        List<RFQPurchaseRequestSource> sources = rfqPurchaseRequestSourceRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids, "ACTIVE");
        List<UUID> rfqIds = sources.stream().map(RFQPurchaseRequestSource::getRfqId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, RFQ> rfqsById = rfqIds.isEmpty()
            ? Map.of()
            : rfqRepository.findByTenantIdAndIdInAndIsDeletedFalse(TenantContext.currentTenant(), rfqIds)
                .stream()
                .filter(this::scoped)
                .collect(Collectors.toMap(RFQ::getId, Function.identity(), (first, second) -> first));
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

    private List<StageRef> stages(CaseContext context, String currentStage) {
        int currentIndex = STAGE_ORDER.indexOf(currentStage);
        return STAGE_ORDER.stream()
            .map(stage -> new StageRef(
                stage,
                state(stage, currentIndex, context),
                documentsForStage(stage, context)))
            .toList();
    }

    private String state(String stage, int currentIndex, CaseContext context) {
        if (!available(stage, context)) {
            return "UNSTARTED";
        }
        int index = STAGE_ORDER.indexOf(stage);
        if (index < currentIndex) {
            return "COMPLETED";
        }
        if (index == currentIndex) {
            return blocked(context) ? "BLOCKED" : "IN_PROGRESS";
        }
        return "NOT_STARTED";
    }

    private boolean available(String stage, CaseContext context) {
        return switch (stage) {
            case "DEMAND_PLAN", "PLAN_APPROVAL" -> context.demandPlan() != null;
            case "PURCHASE_REQUEST" -> !context.purchaseRequests().isEmpty() || demandPlanReadyForPurchaseRequest(context);
            case "PR_APPROVAL", "PROCUREMENT_METHOD", "SOURCING", "AWARD" -> !context.purchaseRequests().isEmpty();
            case "PURCHASE_ORDER", "PO_APPROVAL", "SUPPLIER_DISPATCH", "RECEIPT_OR_ACCEPTANCE", "INVOICING", "PAYMENT", "FULFILLED" ->
                !context.purchaseOrders().isEmpty();
            default -> true;
        };
    }

    private boolean demandPlanReadyForPurchaseRequest(CaseContext context) {
        if (context.demandPlan() == null) {
            return false;
        }
        return Set.of("APPROVED", "ACTIVE").contains(context.demandPlan().getStatus());
    }

    private String currentStage(CaseContext context) {
        if (context.purchaseOrders().stream().anyMatch(order -> Set.of("RECEIVED", "COMPLETED", "CLOSED").contains(order.getStatus()))) {
            return "FULFILLED";
        }
        if (context.purchaseOrders().stream().anyMatch(order -> Set.of("SENT", "PARTIALLY_RECEIVED", "PARTIALLY_COMPLETED").contains(order.getStatus()))) {
            return "RECEIPT_OR_ACCEPTANCE";
        }
        if (context.purchaseOrders().stream().anyMatch(order -> "APPROVED".equals(order.getStatus()))) {
            return "SUPPLIER_DISPATCH";
        }
        if (context.purchaseOrders().stream().anyMatch(order -> "APPROVAL_PENDING".equals(order.getStatus()))) {
            return "PO_APPROVAL";
        }
        if (!context.purchaseOrders().isEmpty()) {
            return "PURCHASE_ORDER";
        }
        if (context.procedures().stream().anyMatch(procedure -> procedure.getAwardedSupplierId() != null || procedure.getPurchaseOrderId() != null || procedure.getContractId() != null)) {
            return "AWARD";
        }
        if (!context.rfqs().isEmpty()) {
            return "SOURCING";
        }
        if (!context.procedures().isEmpty() || !context.methodDecisions().isEmpty()) {
            return "PROCUREMENT_METHOD";
        }
        if (context.purchaseRequests().stream().anyMatch(this::purchaseRequestInSourcing)) {
            return "SOURCING";
        }
        if (context.purchaseRequests().stream().anyMatch(request -> "APPROVAL_PENDING".equals(request.getStatus()))) {
            return "PR_APPROVAL";
        }
        if (context.purchaseRequests().stream().anyMatch(request -> "APPROVED".equals(request.getStatus()))) {
            return "PROCUREMENT_METHOD";
        }
        if (!context.purchaseRequests().isEmpty()) {
            return "PURCHASE_REQUEST";
        }
        if (context.demandPlan() != null && "SUBMITTED".equals(context.demandPlan().getStatus())) {
            return "PLAN_APPROVAL";
        }
        if (demandPlanReadyForPurchaseRequest(context)) {
            return "PURCHASE_REQUEST";
        }
        return "DEMAND_PLAN";
    }

    private boolean purchaseRequestInSourcing(PurchaseRequest request) {
        return Set.of(
            "RFQ_CREATED",
            "PARTIALLY_SENT_TO_TENDER",
            "FULLY_SENT_TO_TENDER"
        ).contains(request.getStatus());
    }

    private boolean blocked(CaseContext context) {
        return context.purchaseRequests().stream().anyMatch(request -> Set.of("REJECTED", "CANCELLED").contains(request.getStatus()))
            || context.purchaseOrders().stream().anyMatch(order -> Set.of("REJECTED", "CANCELLED", "BLOCKED").contains(order.getStatus()))
            || context.rfqs().stream().anyMatch(rfq -> "BLOCKED".equals(rfq.getStatus()));
    }

    private List<DocumentRef> documentsForStage(String stage, CaseContext context) {
        return switch (stage) {
            case "DEMAND_PLAN", "PLAN_APPROVAL" -> context.demandPlan() == null ? List.of() : List.of(ref("DEMAND_PLAN", context.demandPlan()));
            case "PURCHASE_REQUEST", "PR_APPROVAL" -> context.purchaseRequests().stream().map(request -> ref("PURCHASE_REQUEST", request)).toList();
            case "PROCUREMENT_METHOD" -> methodDocuments(context);
            case "SOURCING" -> context.rfqs().stream().map(rfq -> ref("RFQ", rfq)).toList();
            case "AWARD" -> context.procedures().stream().map(procedure -> ref("PROCUREMENT_PROCEDURE", procedure)).toList();
            case "PURCHASE_ORDER", "PO_APPROVAL", "SUPPLIER_DISPATCH", "RECEIPT_OR_ACCEPTANCE", "INVOICING", "PAYMENT", "FULFILLED" ->
                context.purchaseOrders().stream().map(order -> ref("PURCHASE_ORDER", order)).toList();
            default -> List.of();
        };
    }

    private List<DocumentRef> methodDocuments(CaseContext context) {
        List<DocumentRef> docs = new ArrayList<>();
        context.methodDecisions().stream().map(decision -> ref("PROCUREMENT_METHOD_DECISION", decision)).forEach(docs::add);
        context.procedures().stream().map(procedure -> ref("PROCUREMENT_PROCEDURE", procedure)).forEach(docs::add);
        return docs;
    }

    private List<DocumentRef> relatedDocuments(CaseContext context) {
        LinkedHashMap<String, DocumentRef> docs = new LinkedHashMap<>();
        if (context.demandPlan() != null) {
            put(docs, ref("DEMAND_PLAN", context.demandPlan()));
        }
        context.purchaseRequests().stream().map(request -> ref("PURCHASE_REQUEST", request)).forEach(ref -> put(docs, ref));
        context.methodDecisions().stream().map(decision -> ref("PROCUREMENT_METHOD_DECISION", decision)).forEach(ref -> put(docs, ref));
        context.procedures().stream().map(procedure -> ref("PROCUREMENT_PROCEDURE", procedure)).forEach(ref -> put(docs, ref));
        context.rfqs().stream().map(rfq -> ref("RFQ", rfq)).forEach(ref -> put(docs, ref));
        context.purchaseOrders().stream().map(order -> ref("PURCHASE_ORDER", order)).forEach(ref -> put(docs, ref));
        return new ArrayList<>(docs.values());
    }

    private List<SourceWarning> sourceWarnings(List<PurchaseOrder> orders) {
        List<SourceWarning> warnings = new ArrayList<>();
        for (PurchaseOrder order : orders) {
            if (order.getPurchaseRequestId() == null) {
                continue;
            }
            List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), order.getId())
                .stream()
                .filter(this::scoped)
                .toList();
            if (items.isEmpty()) {
                continue;
            }
            List<UUID> itemIds = items.stream().map(PurchaseOrderItem::getId).filter(Objects::nonNull).toList();
            Set<UUID> itemsWithAllocation = purchaseOrderSourceAllocationRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), itemIds)
                .stream()
                .map(PurchaseOrderSourceAllocation::getPurchaseOrderItemId)
                .collect(Collectors.toSet());
            boolean missing = items.stream().anyMatch(item -> !itemsWithAllocation.contains(item.getId()));
            if (missing) {
                warnings.add(new SourceWarning(
                    "procurement_case.missing_po_source_allocation",
                    "PR-backed purchase order has one or more lines without authoritative source allocation.",
                    order.getId()));
            }
        }
        return warnings;
    }

    private DocumentRef rootRef(RootKind rootKind, UUID rootId, CaseContext context) {
        return switch (rootKind) {
            case DEMAND_PLAN -> ref("DEMAND_PLAN", context.demandPlan());
            case PURCHASE_REQUEST -> ref("PURCHASE_REQUEST", context.purchaseRequests().stream()
                .filter(request -> Objects.equals(rootId, request.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("PurchaseRequest not found in procurement case: " + rootId)));
            case PURCHASE_ORDER -> ref("PURCHASE_ORDER", context.purchaseOrders().stream()
                .filter(order -> Objects.equals(rootId, order.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("PurchaseOrder not found in procurement case: " + rootId)));
        };
    }

    private DocumentRef ref(String documentType, com.enterprise.mm.common.BusinessEntity entity) {
        if (entity == null) {
            return null;
        }
        return new DocumentRef(
            documentType,
            entity.getId(),
            moduleKey(documentType),
            entity.getCode(),
            entity.getName(),
            entity.getStatus()
        );
    }

    private String moduleKey(String documentType) {
        return switch (documentType) {
            case "DEMAND_PLAN" -> "demand-plans";
            case "PURCHASE_REQUEST" -> "purchase-requests";
            case "PROCUREMENT_METHOD_DECISION" -> "procurement-method-decisions";
            case "PROCUREMENT_PROCEDURE" -> "procurement-procedures";
            case "RFQ" -> "rfq";
            case "PURCHASE_ORDER" -> "purchase-orders";
            default -> documentType.toLowerCase(Locale.ROOT);
        };
    }

    private void put(Map<String, DocumentRef> docs, DocumentRef ref) {
        if (ref != null && ref.documentId() != null) {
            docs.putIfAbsent(ref.documentType() + ":" + ref.documentId(), ref);
        }
    }

    private RootKind parseRoot(String value) {
        try {
            return RootKind.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException("Unsupported procurement case root type: " + value);
        }
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && Objects.equals(TenantContext.currentTenant(), entity.getTenantId())
            && Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId());
    }

    private enum RootKind {
        DEMAND_PLAN,
        PURCHASE_REQUEST,
        PURCHASE_ORDER
    }

    private record CaseContext(
        DemandPlan demandPlan,
        List<PurchaseRequest> purchaseRequests,
        List<ProcurementMethodDecision> methodDecisions,
        List<ProcurementProcedure> procedures,
        List<RFQ> rfqs,
        List<PurchaseOrder> purchaseOrders
    ) {
        private CaseContext {
            purchaseRequests = purchaseRequests == null ? List.of() : purchaseRequests.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PurchaseRequest::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
            methodDecisions = methodDecisions == null ? List.of() : methodDecisions;
            procedures = procedures == null ? List.of() : procedures;
            rfqs = rfqs == null ? List.of() : rfqs;
            purchaseOrders = purchaseOrders == null ? List.of() : purchaseOrders.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PurchaseOrder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        }
    }
}
