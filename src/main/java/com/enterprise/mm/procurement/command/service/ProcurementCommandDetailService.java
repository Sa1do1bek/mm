package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseHeader;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandBlocker;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandAction;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseDetail;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.PurchaseOrderReadiness;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QuotationComparisonRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.RelatedDocument;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ApprovalContext;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ApprovalTaskContext;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentContext;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.EntityRef;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.EvidenceContext;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.IntegrationImpactContext;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.LifecycleItem;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.service.approval.PurchaseOrderApprovalAdapter;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.security.AccessScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcurementCommandDetailService {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final RFQRepository rfqRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final SourcingCoverageReader coverageReader;
    private final ProcurementBlockerResolver blockerResolver;
    private final ProcurementNextActionResolver nextActionResolver;
    private final PurchaseOrderDispatchReadinessService readinessService;
    private final ProcurementWorkAssignmentRepository assignmentRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final AttachmentRepository attachmentRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final SupplierRepository supplierRepository;

    public ProcurementCommandDetailService(
        PurchaseRequestRepository purchaseRequestRepository,
        RFQRepository rfqRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        SupplierQuotationRepository quotationRepository,
        RFQPurchaseRequestSourceRepository sourceRepository,
        SourcingCoverageReader coverageReader,
        ProcurementBlockerResolver blockerResolver,
        ProcurementNextActionResolver nextActionResolver,
        PurchaseOrderDispatchReadinessService readinessService,
        ProcurementWorkAssignmentRepository assignmentRepository,
        ApprovalInstanceRepository approvalInstanceRepository,
        ApprovalTaskRepository approvalTaskRepository,
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        SupplierRepository supplierRepository
    ) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.rfqRepository = rfqRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.quotationRepository = quotationRepository;
        this.sourceRepository = sourceRepository;
        this.coverageReader = coverageReader;
        this.blockerResolver = blockerResolver;
        this.nextActionResolver = nextActionResolver;
        this.readinessService = readinessService;
        this.assignmentRepository = assignmentRepository;
        this.approvalInstanceRepository = approvalInstanceRepository;
        this.approvalTaskRepository = approvalTaskRepository;
        this.attachmentRepository = attachmentRepository;
        this.integrationEventRepository = integrationEventRepository;
        this.supplierRepository = supplierRepository;
    }

    @Transactional(readOnly = true)
    public ProcurementCommandCaseDetail get(UUID purchaseRequestId) {
        PurchaseRequest request = findRequest(purchaseRequestId);
        List<RFQ> rfqs = relatedRfqs(purchaseRequestId);
        List<PurchaseOrder> purchaseOrders = purchaseOrders(purchaseRequestId);
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus(request.getStatus())
            .rfqStatuses(rfqs.stream().map(RFQ::getStatus).toList())
            .approvalPending("APPROVAL_PENDING".equalsIgnoreCase(request.getStatus()))
            .approvalRejected("APPROVAL_REJECTED".equalsIgnoreCase(request.getStatus())
                || "REJECTED".equalsIgnoreCase(request.getStatus()))
            .quotationReady(rfqs.stream().anyMatch(rfq -> List.of("EVALUATED", "QUOTATIONS_RECEIVED").contains(rfq.getStatus())))
            .poDraft(purchaseOrders.stream().anyMatch(po -> "DRAFT".equals(po.getStatus())))
            .poReady(purchaseOrders.stream().anyMatch(po -> "APPROVED".equals(po.getStatus())))
            .build();
        List<CommandBlocker> blockers = blockerResolver.resolveBlockers(facts);
        AvailableActions actions = bindActionTargets(
            nextActionResolver.resolveActions(facts, blockers), request, rfqs, purchaseOrders);
        List<RelatedDocument> documents = new ArrayList<>();
        documents.addAll(rfqs.stream()
            .map(rfq -> new RelatedDocument("RFQ", rfq.getId(), rfq.getCode(), rfq.getStatus()))
            .toList());
        documents.addAll(purchaseOrders.stream()
            .map(po -> new RelatedDocument("PURCHASE_ORDER", po.getId(), po.getCode(), po.getStatus()))
            .toList());
        List<PurchaseOrderReadiness> readiness = purchaseOrders.stream()
            .map(po -> new PurchaseOrderReadiness(
                po.getId(), po.getCode(), po.getStatus(), supplierRef(po), readinessService.evaluate(po.getId())))
            .toList();
        return new ProcurementCommandCaseDetail(
            new CaseHeader(
                request.getId(), request.getCode(), request.getName(), request.getStatus(),
                request.getRequestedBy(), request.getDepartmentId(), request.getRequiredDate(),
                request.getTotalAmount(), request.getCurrencyCode()),
            coverageReader.read(purchaseRequestId), blockers, actions, readiness,
            List.copyOf(documents), List.of(), lifecycle(request, rfqs, purchaseOrders),
            assignment(purchaseRequestId), approvals(request, purchaseOrders),
            evidence(request, purchaseOrders), integrationImpact(request, purchaseOrders), LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public AvailableActions actions(UUID purchaseRequestId) {
        return get(purchaseRequestId).availableActions();
    }

    @Transactional(readOnly = true)
    public Page<QuotationComparisonRow> comparison(UUID purchaseRequestId, Pageable pageable) {
        findRequest(purchaseRequestId);
        List<SupplierQuotation> quotations = relatedRfqs(purchaseRequestId).stream()
            .flatMap(rfq -> currentSubmitted(rfq.getId()).stream())
            .filter(q -> AccessScope.canAccessCompany(q.getCompanyId()))
            .toList();
        int from = Math.min((int) pageable.getOffset(), quotations.size());
        int to = Math.min(from + pageable.getPageSize(), quotations.size());
        List<QuotationComparisonRow> rows = quotations.subList(from, to).stream().map(this::comparisonRow).toList();
        return new PageImpl<>(rows, pageable, quotations.size());
    }

    private List<SupplierQuotation> currentSubmitted(UUID rfqId) {
        List<String> statuses = List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED");
        List<SupplierQuotation> current = quotationRepository.findCurrentSubmittedByRfq(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId, statuses
        );
        if (!current.isEmpty()) {
            return current;
        }
        return quotationRepository
            .findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc(
                TenantContext.currentTenant(), rfqId
            ).stream()
            .filter(value -> value.getThreadId() == null)
            .filter(value -> value.getStatus() == null || statuses.contains(value.getStatus()))
            .toList();
    }

    private QuotationComparisonRow comparisonRow(SupplierQuotation q) {
        return new QuotationComparisonRow(
            q.getId(), q.getCode(), q.getSupplierId(), supplierDisplayName(q), q.getTotalAmount(), q.getCurrencyCode(),
            q.getTechnicalScore(), q.getDeliveryScore(), q.getPriceScore(), q.getTotalScore(), q.isSelected());
    }

    private String supplierDisplayName(SupplierQuotation quotation) {
        if (quotation.getSupplier() == null) {
            return null;
        }
        if (quotation.getSupplier().getShortName() != null && !quotation.getSupplier().getShortName().isBlank()) {
            return quotation.getSupplier().getShortName();
        }
        if (quotation.getSupplier().getLegalName() != null && !quotation.getSupplier().getLegalName().isBlank()) {
            return quotation.getSupplier().getLegalName();
        }
        return null;
    }

    private List<RFQ> relatedRfqs(UUID purchaseRequestId) {
        Map<UUID, RFQ> rfqs = new LinkedHashMap<>();
        rfqRepository.findByTenantIdAndPurchaseRequestIdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequestId)
            .stream()
            .filter(rfq -> AccessScope.canAccessCompany(rfq.getCompanyId()))
            .forEach(rfq -> rfqs.put(rfq.getId(), rfq));
        List<UUID> sourceRfqIds = sourceRepository
            .findByTenantIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestId, RfqSourceAllocationStatus.ACTIVE.name())
            .stream()
            .filter(source -> AccessScope.canAccessCompany(source.getCompanyId()))
            .map(RFQPurchaseRequestSource::getRfqId)
            .distinct()
            .toList();
        if (!sourceRfqIds.isEmpty()) {
            rfqRepository.findByTenantIdAndIdInAndIsDeletedFalse(TenantContext.currentTenant(), sourceRfqIds)
                .stream()
                .filter(rfq -> AccessScope.canAccessCompany(rfq.getCompanyId()))
                .forEach(rfq -> rfqs.putIfAbsent(rfq.getId(), rfq));
        }
        return List.copyOf(rfqs.values());
    }

    private PurchaseRequest findRequest(UUID id) {
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(request -> TenantContext.currentTenant().equals(request.getTenantId()))
            .filter(request -> AccessScope.canAccessCompany(request.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseRequest not found: " + id));
    }

    private List<PurchaseOrder> purchaseOrders(UUID purchaseRequestId) {
        return purchaseOrderRepository
            .findByTenantIdAndPurchaseRequestIdIsNotNullAndIsDeletedFalse(TenantContext.currentTenant())
            .stream()
            .filter(po -> purchaseRequestId.equals(po.getPurchaseRequestId()))
            .filter(po -> AccessScope.canAccessCompany(po.getCompanyId()))
            .toList();
    }

    private EntityRef supplierRef(PurchaseOrder purchaseOrder) {
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrder.getSupplierId()
        ).filter(entity -> AccessScope.canAccessCompany(entity.getCompanyId())).orElse(null);
        if (supplier == null) {
            return new EntityRef(purchaseOrder.getSupplierId(), null, "Supplier unavailable", null);
        }
        return new EntityRef(
            supplier.getId(), supplier.getCode(), displayName(supplier.getName(), supplier.getLegalName(), supplier.getCode()),
            supplier.getStatus());
    }

    private AvailableActions bindActionTargets(
        AvailableActions actions,
        PurchaseRequest request,
        List<RFQ> rfqs,
        List<PurchaseOrder> purchaseOrders
    ) {
        if (actions == null) return null;
        CommandAction primary = bindActionTarget(actions.primary(), request, rfqs, purchaseOrders);
        List<CommandAction> secondary = actions.secondary() == null ? List.of() : actions.secondary().stream()
            .map(action -> bindActionTarget(action, request, rfqs, purchaseOrders))
            .toList();
        return new AvailableActions(primary, secondary);
    }

    private CommandAction bindActionTarget(
        CommandAction action,
        PurchaseRequest request,
        List<RFQ> rfqs,
        List<PurchaseOrder> purchaseOrders
    ) {
        if (action == null || action.documentId() != null) return action;
        String key = action.key() == null ? "" : action.key();
        String documentType = "PURCHASE_REQUEST";
        UUID documentId = request.getId();
        if (isPurchaseOrderAction(key) && !purchaseOrders.isEmpty()) {
            PurchaseOrder target = purchaseOrderTarget(key, purchaseOrders);
            documentType = "PURCHASE_ORDER";
            documentId = target.getId();
        } else if (isRfqAction(key) && !rfqs.isEmpty()) {
            RFQ target = rfqs.get(0);
            documentType = "RFQ";
            documentId = target.getId();
        }
        return new CommandAction(
            action.key(), action.enabled(), action.disabledReasonCode(), action.disabledReasonParameters(),
            action.commentRequired(), action.invocationKind(), documentType, documentId);
    }

    private boolean isPurchaseOrderAction(String key) {
        return key.contains("PO") || key.contains("PURCHASE_ORDER") || key.contains("CONTRACT")
            || key.equals("TRACK_DELIVERY");
    }

    private boolean isRfqAction(String key) {
        return key.contains("RFQ") || key.contains("TENDER") || key.contains("QUOTATION") || key.contains("AWARD");
    }

    private PurchaseOrder purchaseOrderTarget(String key, List<PurchaseOrder> purchaseOrders) {
        if ("DISPATCH_PURCHASE_ORDER".equals(key)) {
            return purchaseOrders.stream().filter(po -> "APPROVED".equals(po.getStatus()))
                .findFirst().orElse(purchaseOrders.get(0));
        }
        if ("COMPLETE_PURCHASE_ORDER".equals(key)) {
            return purchaseOrders.stream().filter(po -> List.of("DRAFT", "RETURNED").contains(po.getStatus()))
                .findFirst().orElse(purchaseOrders.get(0));
        }
        return purchaseOrders.get(0);
    }

    private List<LifecycleItem> lifecycle(
        PurchaseRequest request,
        List<RFQ> rfqs,
        List<PurchaseOrder> purchaseOrders
    ) {
        List<LifecycleItem> items = new ArrayList<>();
        items.add(new LifecycleItem(
            "NEED", "PURCHASE_REQUEST", request.getId(), request.getCode(), request.getStatus(),
            displayName(request.getName(), request.getCode()), "/purchase-requests?search=" + request.getCode()));
        rfqs.forEach(rfq -> items.add(new LifecycleItem(
            "SOURCING", "RFQ", rfq.getId(), rfq.getCode(), rfq.getStatus(),
            displayName(rfq.getName(), rfq.getCode()), "/rfq?search=" + rfq.getCode())));
        purchaseOrders.forEach(po -> items.add(new LifecycleItem(
            "ORDER", "PURCHASE_ORDER", po.getId(), po.getCode(), po.getStatus(),
            displayName(po.getName(), po.getCode()), "/purchase-orders?purchaseOrderId=" + po.getId())));
        return List.copyOf(items);
    }

    private AssignmentContext assignment(UUID purchaseRequestId) {
        ProcurementWorkAssignment assignment = assignmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestId)
            .orElse(null);
        if (assignment == null) return null;
        return new AssignmentContext(
            assignment.getId(), assignment.getOwnerUserId(), assignment.getOwnerUsername(), assignment.getOwnerRole(),
            assignment.getDueDate(), assignment.getSeverity(), assignment.isEscalated(), assignment.getEscalationLevel(),
            assignment.getReason(), assignment.getVersion());
    }

    private List<ApprovalContext> approvals(PurchaseRequest request, List<PurchaseOrder> purchaseOrders) {
        List<ApprovalContext> result = new ArrayList<>();
        approval(request.getCompanyId(), PurchaseRequestApprovalAdapter.MODULE_KEY, request.getId(), request.getCode())
            .ifPresent(result::add);
        purchaseOrders.forEach(po -> approval(
            po.getCompanyId(), PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId(), po.getCode()).ifPresent(result::add));
        return List.copyOf(result);
    }

    private java.util.Optional<ApprovalContext> approval(
        UUID companyId,
        String moduleKey,
        UUID documentId,
        String documentCode
    ) {
        return approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(), companyId, moduleKey, documentId)
            .map(instance -> approvalContext(instance, documentCode));
    }

    private ApprovalContext approvalContext(ApprovalInstance instance, String documentCode) {
        List<ApprovalTaskContext> tasks = approvalTaskRepository
            .findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(), instance.getId())
            .stream().map(task -> new ApprovalTaskContext(
                task.getId(), task.getStepOrder(), task.getTaskStatus() == null ? null : task.getTaskStatus().name(),
                task.getAssigneeRole(), task.getAssigneeUserId(), task.getAssigneeUsername(), task.getActedBy(),
                task.getActedAt(), task.getCommentText())).toList();
        return new ApprovalContext(
            instance.getId(), instance.getModuleKey(), instance.getEntityId(), documentCode,
            instance.getApprovalStatus() == null ? null : instance.getApprovalStatus().name(),
            instance.getCurrentStepOrder(), instance.getStartedAt(), instance.getCompletedAt(), tasks);
    }

    private List<EvidenceContext> evidence(PurchaseRequest request, List<PurchaseOrder> purchaseOrders) {
        List<EvidenceContext> result = new ArrayList<>();
        appendEvidence(result, request.getCompanyId(), "PURCHASEREQUEST", request.getId(), request.getCode());
        purchaseOrders.forEach(po -> appendEvidence(
            result, po.getCompanyId(), "PURCHASEORDER", po.getId(), po.getCode()));
        return List.copyOf(result);
    }

    private void appendEvidence(
        List<EvidenceContext> target,
        UUID companyId,
        String entityType,
        UUID entityId,
        String documentCode
    ) {
        attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, entityType, entityId)
            .stream().map(attachment -> evidenceContext(attachment, documentCode)).forEach(target::add);
    }

    private EvidenceContext evidenceContext(Attachment attachment, String documentCode) {
        return new EvidenceContext(
            attachment.getId(), attachment.getEntityType(), attachment.getEntityId(), documentCode,
            displayName(attachment.getOriginalFileName(), attachment.getName(), attachment.getCode()),
            attachment.getAttachmentType(), attachment.getContentType(), attachment.getSizeBytes(),
            attachment.getUploadedAt(), attachment.isPreviewable(), attachment.isDownloadable());
    }

    private List<IntegrationImpactContext> integrationImpact(
        PurchaseRequest request,
        List<PurchaseOrder> purchaseOrders
    ) {
        Map<UUID, IntegrationImpactContext> result = new LinkedHashMap<>();
        appendIntegration(result, request.getCompanyId(), request.getId(), request.getCode());
        purchaseOrders.forEach(po -> appendIntegration(result, po.getCompanyId(), po.getId(), po.getCode()));
        return List.copyOf(result.values());
    }

    private void appendIntegration(
        Map<UUID, IntegrationImpactContext> target,
        UUID companyId,
        UUID aggregateId,
        String documentCode
    ) {
        integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, aggregateId)
            .stream().map(event -> integrationContext(event, documentCode))
            .forEach(event -> target.putIfAbsent(event.id(), event));
    }

    private IntegrationImpactContext integrationContext(IntegrationEvent event, String documentCode) {
        return new IntegrationImpactContext(
            event.getId(), event.getAggregateId(), documentCode, event.getEventType(), event.getStatus(),
            event.getRetryCount(), event.getLastError(), event.getCreatedAt());
    }

    private String displayName(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return "вЂ”";
    }
}
