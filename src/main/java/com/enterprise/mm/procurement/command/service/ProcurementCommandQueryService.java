package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandBlocker;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementCommandQueryRepository;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.security.AccessScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcurementCommandQueryService {

    private final ProcurementCommandQueryRepository repository;
    private final RFQRepository rfqRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProcurementStageResolver stageResolver;
    private final ProcurementBlockerResolver blockerResolver;
    private final ProcurementNextActionResolver nextActionResolver;
    private ProcurementWorkAssignmentRepository assignmentRepository;

    public ProcurementCommandQueryService(
        ProcurementCommandQueryRepository repository,
        RFQRepository rfqRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        ProcurementStageResolver stageResolver,
        ProcurementBlockerResolver blockerResolver,
        ProcurementNextActionResolver nextActionResolver
    ) {
        this.repository = repository;
        this.rfqRepository = rfqRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.stageResolver = stageResolver;
        this.blockerResolver = blockerResolver;
        this.nextActionResolver = nextActionResolver;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setAssignmentRepository(ProcurementWorkAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProcurementCommandCaseRow> search(
        ProcurementCommandSearchRequest request,
        Pageable pageable
    ) {
        return search(request, pageable, false);
    }

    @Transactional(readOnly = true)
    public Page<ProcurementCommandCaseRow> searchForCompany(
        ProcurementCommandSearchRequest request,
        Pageable pageable
    ) {
        return search(request, pageable, true);
    }

    private Page<ProcurementCommandCaseRow> search(
        ProcurementCommandSearchRequest request,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        Page<PurchaseRequest> page = forceCompanyScope
            ? repository.searchForCompany(request, pageable)
            : repository.search(request, pageable);
        List<UUID> purchaseRequestIds = page.getContent().stream().map(PurchaseRequest::getId).toList();
        if (purchaseRequestIds.isEmpty()) {
            return page.map(item -> toRow(item, List.of(), List.of(), null, forceCompanyScope));
        }
        Map<UUID, List<RFQ>> rfqsByPurchaseRequest = groupRfqs(rfqRepository
            .findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestIds));
        Map<UUID, List<PurchaseOrder>> ordersByPurchaseRequest = groupPurchaseOrders(purchaseOrderRepository
            .findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestIds));
        Map<UUID, ProcurementWorkAssignment> assignments = assignments(purchaseRequestIds);
        return page.map(item -> toRow(
            item,
            rfqsByPurchaseRequest.getOrDefault(item.getId(), List.of()),
            ordersByPurchaseRequest.getOrDefault(item.getId(), List.of()),
            item.getId() == null ? null : assignments.get(item.getId()),
            forceCompanyScope));
    }

    private ProcurementCommandCaseRow toRow(
        PurchaseRequest request,
        List<RFQ> relatedRfqs,
        List<PurchaseOrder> relatedPurchaseOrders,
        ProcurementWorkAssignment assignment,
        boolean forceCompanyScope
    ) {
        LocalDate today = LocalDate.now();
        List<RFQ> rfqs = relatedRfqs.stream()
            .filter(rfq -> forceCompanyScope
                ? java.util.Objects.equals(rfq.getCompanyId(), CompanyContext.currentCompany())
                : AccessScope.canAccessCompany(rfq.getCompanyId())).toList();
        List<PurchaseOrder> purchaseOrders = relatedPurchaseOrders.stream()
            .filter(order -> forceCompanyScope
                ? java.util.Objects.equals(order.getCompanyId(), CompanyContext.currentCompany())
                : AccessScope.canAccessCompany(order.getCompanyId())).toList();
        List<String> rfqStatuses = rfqs.stream().map(RFQ::getStatus).toList();
        LocalDate dueDate = assignment != null && assignment.getDueDate() != null
            ? assignment.getDueDate() : request.getRequiredDate();
        boolean overdue = dueDate != null && dueDate.isBefore(today);
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus(request.getStatus())
            .branchStages(branchStages(rfqs, purchaseOrders))
            .rfqStatuses(rfqStatuses)
            .approvalPending("APPROVAL_PENDING".equals(request.getStatus()))
            .approvalRejected("REJECTED".equals(request.getStatus()))
            .quotationReady(rfqStatuses.stream().anyMatch(this::quotationReady))
            .tenderBlocked(rfqStatuses.stream().anyMatch("BLOCKED"::equals))
            .poBlocked(purchaseOrders.stream().anyMatch(order -> List.of(
                "BLOCKED", "APPROVAL_REJECTED", "REJECTED").contains(order.getStatus())))
            .poDraft(purchaseOrders.stream().anyMatch(order -> "DRAFT".equals(order.getStatus())))
            .poReady(purchaseOrders.stream().anyMatch(order -> "APPROVED".equals(order.getStatus())))
            .overdue(overdue)
            .build();
        ProcurementCommandStage stage = stageResolver.resolveStage(facts);
        boolean cancelled = ProcurementCommandStage.CANCELLED == stage;
        List<CommandBlocker> blockers = cancelled ? List.of() : blockerResolver.resolveBlockers(facts);
        AvailableActions actions = cancelled
            ? new AvailableActions(null, List.of())
            : nextActionResolver.resolveActions(facts, blockers);
        CommandBlocker primaryBlocker = blockers.isEmpty() ? null : blockers.get(0);
        return new ProcurementCommandCaseRow(
            request.getId(), request.getCode(), request.getName(), stage,
            request.getRequestedBy(), request.getDepartmentId(), request.getRequiredDate(),
            request.getTotalAmount(), request.getCurrencyCode(), ProcurementAllocationMode.AUTHORITATIVE,
            assignment != null && assignment.getSeverity() != null
                ? parseSeverity(assignment.getSeverity())
                : primaryBlocker == null ? null : primaryBlocker.severity(),
            primaryBlocker == null ? null : primaryBlocker.code(),
            nextAction(stage, actions), assignment == null ? null : assignment.getOwnerUserId(),
            assignment == null ? null : assignment.getOwnerUsername(), dueDate, overdue,
            rfqs.size(), purchaseOrders.size(), false);
    }

    private ProcurementBlockerSeverity parseSeverity(String value) {
        try {
            return ProcurementBlockerSeverity.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Map<UUID, ProcurementWorkAssignment> assignments(List<UUID> purchaseRequestIds) {
        if (assignmentRepository == null || CompanyContext.currentCompany() == null) return Map.of();
        Map<UUID, ProcurementWorkAssignment> result = new HashMap<>();
        assignmentRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestIds
        ).forEach(assignment -> result.put(assignment.getPurchaseRequestId(), assignment));
        return result;
    }

    private List<ProcurementCommandStage> branchStages(List<RFQ> rfqs, List<PurchaseOrder> purchaseOrders) {
        List<ProcurementCommandStage> stages = new ArrayList<>();
        for (RFQ rfq : rfqs) {
            if ("BLOCKED".equals(rfq.getStatus())) {
                stages.add(ProcurementCommandStage.BLOCKED);
            } else if (quotationReady(rfq.getStatus())) {
                stages.add(ProcurementCommandStage.QUOTATION_EVALUATION);
            } else if ("AWARDED".equals(rfq.getStatus())) {
                stages.add(ProcurementCommandStage.AWARD_DECISION);
            } else {
                stages.add(ProcurementCommandStage.RFQ_IN_PROGRESS);
            }
        }
        purchaseOrders.stream()
            .map(PurchaseOrder::getStatus)
            .map(this::purchaseOrderStage)
            .filter(java.util.Objects::nonNull)
            .forEach(stages::add);
        return List.copyOf(stages);
    }

    private ProcurementCommandStage purchaseOrderStage(String status) {
        if ("DRAFT".equals(status) || "RETURNED".equals(status)) return ProcurementCommandStage.PO_PREPARATION;
        if ("APPROVAL_PENDING".equals(status)) return ProcurementCommandStage.PO_APPROVAL;
        if ("APPROVED".equals(status)) return ProcurementCommandStage.PO_READY_TO_DISPATCH;
        if (List.of("SENT", "PARTIALLY_RECEIVED", "RECEIVED", "PARTIALLY_COMPLETED", "COMPLETED", "CLOSED").contains(status)) {
            return ProcurementCommandStage.PO_DISPATCHED;
        }
        if ("BLOCKED".equals(status) || "APPROVAL_REJECTED".equals(status) || "REJECTED".equals(status)) {
            return ProcurementCommandStage.BLOCKED;
        }
        return null;
    }

    private boolean quotationReady(String status) {
        return "EVALUATED".equals(status) || "QUOTATIONS_RECEIVED".equals(status);
    }

    private String nextAction(ProcurementCommandStage stage, AvailableActions actions) {
        return switch (stage) {
            case PO_APPROVAL -> "REVIEW_PO_APPROVAL";
            case PO_DISPATCHED -> "TRACK_DELIVERY";
            case CANCELLED -> null;
            default -> actions.primary() == null ? null : actions.primary().key();
        };
    }

    private Map<UUID, List<RFQ>> groupRfqs(List<RFQ> rfqs) {
        Map<UUID, List<RFQ>> grouped = new HashMap<>();
        for (RFQ rfq : rfqs) {
            if (rfq.getPurchaseRequestId() != null) {
                grouped.computeIfAbsent(rfq.getPurchaseRequestId(), ignored -> new ArrayList<>()).add(rfq);
            }
        }
        return grouped;
    }

    private Map<UUID, List<PurchaseOrder>> groupPurchaseOrders(List<PurchaseOrder> purchaseOrders) {
        Map<UUID, List<PurchaseOrder>> grouped = new HashMap<>();
        for (PurchaseOrder purchaseOrder : purchaseOrders) {
            if (purchaseOrder.getPurchaseRequestId() != null) {
                grouped.computeIfAbsent(purchaseOrder.getPurchaseRequestId(), ignored -> new ArrayList<>()).add(purchaseOrder);
            }
        }
        return grouped;
    }
}
