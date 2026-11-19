package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "reviewQueue", "financeQueue", "options", "get"})
public class ServiceAcceptanceActService {
    private static final Set<String> DELETABLE_STATUSES = Set.of(
            ServiceAcceptanceActStatus.DRAFT.name()
    );
    private static final List<String> PAID_COVERAGE_STATUSES = List.of(
            ServiceAcceptanceActStatus.PAID.name()
    );
    private final ServiceAcceptanceActRepository repository;
    private final ServiceAcceptanceActMapper mapper;
    private final ServiceOrderRepository serviceOrderRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceExecutionRepository executionRepository;
    private final ServiceClaimRepository claimRepository;
    private final ContractService contractService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final BudgetItemService budgetItemService;
    private final AttachmentService attachmentService;
    private final IntegrationEventService integrationEventService;
    private final ApprovalEngineService approvalEngineService;
    private final AuditService auditService;
    private final ServiceReviewHistoryService historyService;
    private final ServiceWorkflowNotificationService notificationService;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceWorkflowAccessService accessService;
    private final ServiceOrderAcceptanceLifecycleService orderLifecycleService;
    private final ProcurementPayablesService procurementPayablesService;


    @Transactional
    public ServiceAcceptanceActResponse accept(UUID serviceOrderId, String acceptedBy, String commissionNotes) {
        return accept(serviceOrderId, acceptedBy, commissionNotes, null, null, null, null);
    }

    @Transactional
    public ServiceAcceptanceActResponse accept(
            UUID serviceOrderId,
            String acceptedBy,
            String commissionNotes,
            BigDecimal requestedAmount,
            String stage,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        ServiceOrder visibleOrder = loadOrder(serviceOrderId);
        requireOrderAccess(visibleOrder);
        ServiceOrder order = lockOrderForAcceptance(visibleOrder);
        if ("CLOSED".equals(order.getStatus())) {
            throw new BusinessException("Service order already accepted or closed");
        }
        if (order.getServiceRequestId() != null) {
            validateAcceptedExecution(order.getServiceRequestId());
        }
        List<ServiceAcceptanceAct> existingActs = repository
                .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                        TenantContext.currentTenant(), order.getCompanyId(), order.getId());
        BigDecimal totalAmount = amount(order);
        BigDecimal alreadyCovered = existingActs.stream()
                .filter(existing -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(existing.getStatus()))
                .map(ServiceAcceptanceAct::getAmount)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingAmount = totalAmount.subtract(alreadyCovered).max(BigDecimal.ZERO);
        BigDecimal actAmount = requestedAmount == null ? remainingAmount : requestedAmount;
        if (actAmount == null || actAmount.signum() <= 0) {
            throw new BusinessException("Service acceptance amount must be positive and the order must have remaining value");
        }
        if (actAmount.compareTo(remainingAmount) > 0) {
            throw new BusinessException("Service acceptance amount exceeds remaining service order value");
        }

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        String baseCode = "ACT-" + order.getCode();
        act.setCode(existingActs.isEmpty() ? baseCode : baseCode + "-" + (existingActs.size() + 1));
        act.setName(BusinessNameGenerator.workflowDocumentName("ServiceAcceptanceAct", order.getName()));
        act.setStatus(ServiceAcceptanceActStatus.DRAFT.name());
        act.setTenantId(order.getTenantId());
        act.setCompanyId(order.getCompanyId());
        act.setServiceRequestId(order.getServiceRequestId());
        act.setPurchaseOrderId(order.getPurchaseOrderId());
        act.setContractId(order.getContractId());
        act.setServiceOrderId(order.getId());
        act.setServiceOrder(order);
        act.setAcceptedAt(LocalDateTime.now());
        act.setAcceptedBy(acceptedBy == null || acceptedBy.isBlank() ? "commission" : acceptedBy);
        act.setAmount(actAmount);
        act.setVatAmount(BigDecimal.ZERO);
        act.setVatRate(BigDecimal.ZERO);
        act.setStage(stage == null || stage.isBlank() ? "FINAL" : stage);
        act.setPeriodStart(periodStart);
        act.setPeriodEnd(periodEnd);
        act.setCommissionNotes(commissionNotes);
        validateAct(act, order);
        rejectDuplicateStagePeriod(act, order);
        ServiceAcceptanceAct saved = repository.save(act);

        String orderStatus = orderLifecycleService.recompute(order.getId());
        syncServicePoLines(saved, order, "SERVICE_ACT_CREATED");
        approvalEngineService.linkItem(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, saved.getId(), saved.getCompanyId(), saved.getCode());
        integrationEventService.emit(
                "MM_SERVICE_ACT_CREATED",
                "ServiceAcceptanceAct",
                saved.getId(),
                Map.ofEntries(
                        Map.entry("serviceActId", saved.getId()),
                        Map.entry("serviceActCode", saved.getCode()),
                        Map.entry("serviceOrderId", order.getId()),
                        Map.entry("serviceOrderCode", order.getCode()),
                        Map.entry("supplierId", Objects.toString(order.getSupplierId(), "")),
                        Map.entry("supplierName", order.getSupplier() != null ? order.getSupplier().getName() : ""),
                        Map.entry("target", "REVIEW"),
                        Map.entry("amount", actAmount),
                        Map.entry("currency", order.getCurrencyCode() == null ? "" : order.getCurrencyCode()),
                        Map.entry("stage", act.getStage() == null ? "" : act.getStage()),
                        Map.entry("periodStart", act.getPeriodStart() == null ? "" : act.getPeriodStart()),
                        Map.entry("periodEnd", act.getPeriodEnd() == null ? "" : act.getPeriodEnd()),
                        Map.entry("orderStatus", Objects.requireNonNullElse(orderStatus, order.getStatus())),
                        Map.entry("actStatus", saved.getStatus())
                )
        );
        auditService.log("ServiceAcceptanceAct", saved.getId(), "ACCEPT_SERVICE", order.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse submit(UUID id) {
        return submitForApproval(id);
    }

    @Transactional
    public ServiceAcceptanceActResponse submitForApproval(UUID id) {
        ServiceAcceptanceAct act = findEntity(id);
        if (!ServiceAcceptanceActStatus.DRAFT.name().equals(act.getStatus())) {
            throw new BusinessException("Only DRAFT service act can be submitted");
        }
        validateAcceptedExecution(act.getServiceRequestId());
        approvalEngineService.start(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, id);
        notificationService.reviewRequired("SERVICE_MANAGER", act.getCode());
        return mapper.toResponse(repository.findByIdAndIsDeletedFalse(id).orElseThrow());
    }

    @Transactional
    public ServiceAcceptanceActResponse documentApprove(UUID id, ServiceActionRequest request) {
        ServiceAcceptanceAct act = findEntity(id);
        if (!ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Only DOCUMENT_REVIEW service act can be document-approved");
        }
        validateReviewedActAttachment(act);
        act.setDocumentReviewedAt(LocalDateTime.now());
        act.setStatus(ServiceAcceptanceActStatus.FINANCE_REVIEW.name());
        ServiceAcceptanceAct saved = repository.save(act);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        historyService.record(saved.getServiceRequestId(), saved.getId(), "DOCUMENT", "APPROVED", request == null ? null : request.comment());
        approvalEngineService.start(ServiceFinanceApprovalAdapter.MODULE_KEY, id);
        notificationService.reviewRequired("FINANCE_CONTROLLER", saved.getCode());
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse documentReject(UUID id, ServiceActionRequest request) {
        ServiceAcceptanceAct act = findEntity(id);
        if (!ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Only DOCUMENT_REVIEW service act can be rejected by document review");
        }
        act.setStatus(ServiceAcceptanceActStatus.REJECTED.name());
        historyService.record(act.getServiceRequestId(), act.getId(), "DOCUMENT", "REJECTED", request == null ? null : request.comment());
        ServiceAcceptanceAct saved = repository.save(act);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse sendToPayment(UUID id, ServiceActionRequest request) {
        return sendToPayment(id, null, request == null ? null : request.comment());
    }

    @Transactional
    public ServiceAcceptanceActResponse sendToPayment(UUID id, String paymentReference, String comment) {
        return sendToPayment(id, paymentReference, comment, null);
    }

    @Transactional
    public ServiceAcceptanceActResponse sendToPayment(UUID id, String paymentReference, String comment, Long expectedVersion) {
        ServiceAcceptanceAct act = lockedEntity(id);
        if (expectedVersion != null && !Objects.equals(act.getVersion(), expectedVersion)) {
            throw new com.enterprise.mm.error.ConflictException(
                    "Service acceptance act was changed by another user; refresh the workspace and retry"
            );
        }
        if (ServiceAcceptanceActStatus.PAID.name().equals(act.getStatus()) || act.getSentToPaymentAt() != null) {
            return mapper.toResponse(act);
        }
        if (!ServiceAcceptanceActStatus.APPROVED.name().equals(act.getStatus())) {
            throw new BusinessException("Payment readiness requires finance-approved service act");
        }
        assertNoOpenClaims(act);
        validateRequiredAttachments(act.getId());
        if (act.getPurchaseOrderId() != null) {
            procurementPayablesService.requirePayableInvoice(act.getPurchaseOrderId(), act.getAmount());
        }
        ServiceOrder order = loadOrder(act.getServiceOrderId());
        consumeFinancialsOnce(act, order);
        act.setFinanceReviewedAt(act.getFinanceReviewedAt() == null ? LocalDateTime.now() : act.getFinanceReviewedAt());
        act.setSentToPaymentAt(LocalDateTime.now());
        act.setPaymentReference(trimToNull(paymentReference));
        act.setStatus(ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name());
        ServiceAcceptanceAct saved = repository.save(act);
        if (saved.getPurchaseOrderId() != null) {
            procurementPayablesService.submitSourceDocumentPayment(
                    saved.getPurchaseOrderId(),
                    saved.getId(),
                    ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT,
                    saved.getAmount(),
                    trimToNull(paymentReference),
                    comment
            );
        }
        orderLifecycleService.recompute(saved.getServiceOrderId());
        updateRequestAfterAct(saved, ServiceRequestStatus.SENT_TO_PAYMENT);
        emitPaymentEvents(saved, order);
        notificationService.paymentReady("FINANCE_CONTROLLER", saved.getCode());
        historyService.record(saved.getServiceRequestId(), saved.getId(), "PAYMENT", "SENT_TO_PAYMENT", comment);
        auditService.log("ServiceAcceptanceAct", saved.getId(), "SEND_TO_PAYMENT", comment == null ? saved.getPaymentReference() : comment, null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse markPaid(UUID id, ServiceActionRequest request) {
        return markPaid(id, null, request == null ? null : request.comment());
    }

    @Transactional
    public ServiceAcceptanceActResponse markPaid(UUID id, String paymentReference, String comment) {
        return markPaid(id, paymentReference, comment, null);
    }

    @Transactional
    public ServiceAcceptanceActResponse markPaid(UUID id, String paymentReference, String comment, Long expectedVersion) {
        ServiceAcceptanceAct act = lockedEntity(id);
        if (expectedVersion != null && !Objects.equals(act.getVersion(), expectedVersion)) {
            throw new com.enterprise.mm.error.ConflictException(
                    "Service acceptance act was changed by another user; refresh the workspace and retry"
            );
        }
        if (ServiceAcceptanceActStatus.PAID.name().equals(act.getStatus()) || act.getPaidAt() != null) {
            recomputePurchaseOrderCompletion(act.getPurchaseOrderId());
            return mapper.toResponse(act);
        }
        if (!ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name().equals(act.getStatus())) {
            throw new BusinessException("Only SENT_TO_PAYMENT service act can be marked paid");
        }
        assertNoOpenClaims(act);
        String externalReference = trimToNull(paymentReference);
        if (externalReference == null) {
            externalReference = trimToNull(act.getPaymentReference());
        }
        if (externalReference == null) {
            throw new BusinessException("External Treasury payment reference is required before marking paid");
        }
        if (act.getPurchaseOrderId() != null) {
            procurementPayablesService.markSourceDocumentPaymentPaid(
                    act.getId(),
                    ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT,
                    externalReference,
                    comment
            );
        }
        act.setPaidAt(LocalDateTime.now());
        act.setPaymentReference(externalReference);
        act.setStatus(ServiceAcceptanceActStatus.PAID.name());
        ServiceAcceptanceAct saved = repository.save(act);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        recomputePurchaseOrderCompletion(saved.getPurchaseOrderId());
        updateRequestAfterAct(saved, ServiceRequestStatus.PAID);
        historyService.record(saved.getServiceRequestId(), saved.getId(), "PAYMENT", "PAID", comment);
        auditService.log("ServiceAcceptanceAct", saved.getId(), "MARK_PAID", comment == null ? saved.getPaymentReference() : comment, null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse rollback(UUID id, ServiceActionRequest request) {
        ServiceAcceptanceAct act = findEntity(id);
        if (ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name().equals(act.getStatus())
                || ServiceAcceptanceActStatus.PAID.name().equals(act.getStatus())) {
            throw new BusinessException("Paid or payment-ready service act cannot be rolled back");
        }
        String comment = request == null ? null : request.comment();
        approvalEngineService.resetForResubmission(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, id, comment);
        approvalEngineService.resetForResubmission(ServiceFinanceApprovalAdapter.MODULE_KEY, id, comment);
        act.setStatus(ServiceAcceptanceActStatus.DRAFT.name());
        ServiceAcceptanceAct saved = repository.save(act);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        historyService.record(saved.getServiceRequestId(), saved.getId(), "SERVICE_ACT", "RETURNED", comment);
        auditService.log("ServiceAcceptanceAct", id, "ROLLBACK", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ServiceAcceptanceActResponse> search(ServiceAcceptanceActSearchRequest request, Pageable pageable) {
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
                ServiceAcceptanceAct.class,
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                request,
                request
        );
        if (accessService.hasFullAccess()) {
            return repository.findAll(spec, pageable).map(mapper::toResponse);
        }
        Page<ServiceAcceptanceAct> records = repository.findAll(spec, Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
                records.getContent(),
                pageable,
                entity -> accessService.canAccessServiceAcceptanceAct(
                        entity,
                        loadRequest(entity.getServiceRequestId()),
                        loadOrderOrNull(entity.getServiceOrderId())
                ),
                mapper::toResponse
        );
    }

    @Transactional(readOnly = true)
    public Page<ServiceAcceptanceActResponse> reviewQueue(Pageable pageable) {
        return search(new ServiceAcceptanceActSearchRequest(
                null,
                List.of(
                        ServiceAcceptanceActStatus.DRAFT.name(),
                        ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name(),
                        ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name(),
                        ServiceAcceptanceActStatus.REJECTED.name()
                ),
                null,
                null,
                LocalDate.now().minusYears(5),
                LocalDate.now().plusYears(5),
                false
        ), pageable);
    }

    @Transactional(readOnly = true)
    public Page<ServiceAcceptanceActResponse> financeQueue(Pageable pageable) {
        return search(new ServiceAcceptanceActSearchRequest(
                null,
                List.of(
                        ServiceAcceptanceActStatus.FINANCE_REVIEW.name(),
                        ServiceAcceptanceActStatus.APPROVED.name(),
                        ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name()
                ),
                null,
                null,
                LocalDate.now().minusYears(5),
                LocalDate.now().plusYears(5),
                false
        ), pageable);
    }

    @Transactional(readOnly = true)
    public ServiceOptionsResponse options() {
        return new ServiceOptionsResponse(
                List.of("DRAFT", "APPROVED", "ACCEPTED", "CLOSED", "REJECTED", "CANCELLED"),
                Arrays.stream(ServiceAcceptanceActStatus.values()).map(Enum::name).toList(),
                List.of("WORK", "SERVICE", "MAINTENANCE", "REPAIR", "LOGISTICS"),
                List.of("SUBMIT", "APPROVE", "REJECT", "ROLLBACK", "DOCUMENT_APPROVE", "DOCUMENT_REJECT", "SEND_TO_PAYMENT", "MARK_PAID"),
                List.of("TECHNICAL_SPECIFICATION", "PHOTO", "CONTRACTOR_REPORT", "ACT", "INVOICE", "CLAIM_DOCUMENT")
        );
    }

    @Transactional(readOnly = true)
    public ServiceAcceptanceActResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ServiceAcceptanceActResponse create(ServiceAcceptanceActCreateRequest dto) {
        ServiceAcceptanceAct entity = mapper.toEntity(dto);
        beforeCreate(entity);
        ServiceAcceptanceAct saved = repository.save(entity);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        approvalEngineService.linkItem(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, saved.getId(), saved.getCompanyId(), saved.getCode());
        auditService.log("ServiceAcceptanceAct", saved.getId(), "CREATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceAcceptanceActResponse update(UUID id, ServiceAcceptanceActUpdateRequest dto) {
        ServiceAcceptanceAct entity = findEntity(id);
        if (!ServiceAcceptanceActStatus.DRAFT.name().equals(entity.getStatus()) && !"RETURNED".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or RETURNED service acts can be edited");
        }
        String currentStatus = entity.getStatus();
        LocalDateTime currentDocumentReviewedAt = entity.getDocumentReviewedAt();
        LocalDateTime currentFinanceReviewedAt = entity.getFinanceReviewedAt();
        LocalDateTime currentSentToPaymentAt = entity.getSentToPaymentAt();
        LocalDateTime currentPaidAt = entity.getPaidAt();
        LocalDateTime currentBudgetConsumedAt = entity.getBudgetConsumedAt();
        LocalDateTime currentContractConsumedAt = entity.getContractConsumedAt();
        String currentPaymentReference = entity.getPaymentReference();
        mapper.updateEntity(entity, dto);
        entity.setStatus(currentStatus);
        entity.setDocumentReviewedAt(currentDocumentReviewedAt);
        entity.setFinanceReviewedAt(currentFinanceReviewedAt);
        entity.setSentToPaymentAt(currentSentToPaymentAt);
        entity.setPaidAt(currentPaidAt);
        entity.setBudgetConsumedAt(currentBudgetConsumedAt);
        entity.setContractConsumedAt(currentContractConsumedAt);
        entity.setPaymentReference(currentPaymentReference);
        beforeUpdate(entity);
        ServiceAcceptanceAct saved = repository.save(entity);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        auditService.log("ServiceAcceptanceAct", saved.getId(), "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        ServiceAcceptanceAct entity = findEntity(id);
        requireDeletableStatus(entity, "deleted");
        entity.softDelete();
        repository.save(entity);
        orderLifecycleService.recompute(entity.getServiceOrderId());
        auditService.log("ServiceAcceptanceAct", id, "SOFT_DELETE", entity.getCode(), null, null);
    }

    @Transactional
    public ServiceAcceptanceActResponse restore(UUID id) {
        ServiceAcceptanceAct entity = repository.findAnyById(id)
                .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
                || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        if (!accessService.canAccessServiceAcceptanceAct(entity, loadRequest(entity.getServiceRequestId()), loadOrderOrNull(entity.getServiceOrderId()))) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        requireDeletableStatus(entity, "restored");
        entity.restore();
        ServiceAcceptanceAct saved = repository.save(entity);
        orderLifecycleService.recompute(saved.getServiceOrderId());
        return mapper.toResponse(saved);
    }

    private void requireDeletableStatus(ServiceAcceptanceAct entity, String action) {
        if (!DELETABLE_STATUSES.contains(entity.getStatus())) {
            throw new BusinessException("Service acceptance act in status " + entity.getStatus() + " cannot be " + action);
        }
    }

    protected ServiceAcceptanceAct findEntity(UUID id) {
        ServiceAcceptanceAct entity = repository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
                || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        if (!accessService.canAccessServiceAcceptanceAct(entity, loadRequest(entity.getServiceRequestId()), loadOrderOrNull(entity.getServiceOrderId()))) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        return entity;
    }

    protected void beforeCreate(ServiceAcceptanceAct entity) {
        ServiceOrder visibleOrder = loadOrder(entity.getServiceOrderId());
        requireOrderAccess(visibleOrder);
        ServiceOrder order = lockOrderForAcceptance(visibleOrder);
        validateActLinks(entity, order);
        entity.setServiceRequestId(order.getServiceRequestId());
        if (entity.getServiceRequestId() != null) {
            validateAcceptedExecution(entity.getServiceRequestId());
        }
        entity.setStatus(ServiceAcceptanceActStatus.DRAFT.name());
        entity.setCompanyId(order.getCompanyId());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("SAA-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), order.getName()
        ));
        entity.setServiceOrderId(order.getId());
        entity.setPurchaseOrderId(order.getPurchaseOrderId());
        entity.setContractId(order.getContractId());
        entity.setAcceptedAt(entity.getAcceptedAt() == null ? LocalDateTime.now() : entity.getAcceptedAt());
        validateAct(entity, order);
        rejectDuplicateStagePeriod(entity, order);
        entity.setDocumentReviewedAt(null);
        entity.setFinanceReviewedAt(null);
        entity.setSentToPaymentAt(null);
        entity.setPaidAt(null);
        entity.setBudgetConsumedAt(null);
        entity.setContractConsumedAt(null);
        entity.setPaymentReference(null);
    }

    protected void beforeUpdate(ServiceAcceptanceAct entity) {
        ServiceOrder order = loadOrder(entity.getServiceOrderId());
        requireOrderAccess(order);
        validateActLinks(entity, order);
        entity.setServiceRequestId(order.getServiceRequestId());
        entity.setCompanyId(order.getCompanyId());
        entity.setPurchaseOrderId(order.getPurchaseOrderId());
        entity.setContractId(order.getContractId());
        if (entity.getServiceRequestId() != null) {
            validateAcceptedExecution(entity.getServiceRequestId());
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
                "ServiceAcceptanceAct", entity.getName(), entity.getCode(), order.getName()
        ));
        validateAct(entity, order);
        rejectDuplicateStagePeriod(entity, order);
    }

    private void validateAcceptedExecution(UUID serviceRequestId) {
        if (serviceRequestId == null) {
            return;
        }
        List<ServiceExecution> executions = executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TenantContext.currentTenant(), serviceRequestId);
        if (executions.isEmpty()) {
            throw new BusinessException("Acceptance cannot be created before execution");
        }
        boolean accepted = executions.stream().anyMatch(item ->
                ServiceExecutionStatus.ACCEPTED.name().equals(item.getStatus())
                        || ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name().equals(item.getStatus()));
        boolean allRequiredFinished = executions.stream().allMatch(item -> List.of(
                ServiceExecutionStatus.ACCEPTED.name(),
                ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
                ServiceExecutionStatus.CANCELLED.name()
        ).contains(item.getStatus()));
        if (!accepted || !allRequiredFinished) {
            throw new BusinessException("Acceptance requires all required service executions to be accepted or cancelled");
        }
    }

    private void validateActLinks(ServiceAcceptanceAct act, ServiceOrder order) {
        if (act.getServiceRequestId() != null && !Objects.equals(act.getServiceRequestId(), order.getServiceRequestId())) {
            throw new BusinessException("Service act service request does not belong to service order");
        }
        if (act.getPurchaseOrderId() != null && !Objects.equals(act.getPurchaseOrderId(), order.getPurchaseOrderId())) {
            throw new BusinessException("Service act purchase order does not match service order");
        }
        if (act.getContractId() != null && !Objects.equals(act.getContractId(), order.getContractId())) {
            throw new BusinessException("Service act contract does not match service order");
        }
    }

    private void validateAct(ServiceAcceptanceAct act, ServiceOrder order) {
        if (act.getAmount() == null || act.getAmount().signum() <= 0) {
            throw new BusinessException("Service act amount must be positive");
        }
        act.setVatAmount(nvl(act.getVatAmount()));
        act.setVatRate(nvl(act.getVatRate()));
        if (act.getVatAmount().signum() < 0) {
            throw new BusinessException("Service act VAT amount cannot be negative");
        }
        if (act.getVatRate().signum() < 0 || act.getVatRate().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("Service act VAT rate must be between 0 and 100");
        }
        if (act.getVatAmount().compareTo(act.getAmount()) > 0) {
            throw new BusinessException("Service act VAT amount cannot exceed act amount");
        }
        if (act.getVatRate().signum() == 0 && act.getVatAmount().signum() > 0) {
            throw new BusinessException("Service act VAT amount requires a positive VAT rate");
        }
        if (act.getPeriodStart() != null && act.getPeriodEnd() != null
                && act.getPeriodEnd().isBefore(act.getPeriodStart())) {
            throw new BusinessException("Service act period end cannot precede period start");
        }
        act.setStage(normalizeStage(act.getStage()));
        if (order.getTotalAmount() != null && order.getTotalAmount().signum() > 0) {
            BigDecimal existingAmount = repository
                    .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                            TenantContext.currentTenant(), order.getCompanyId(), order.getId())
                    .stream()
                    .filter(existing -> !Objects.equals(existing.getId(), act.getId()))
                    .filter(existing -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(existing.getStatus()))
                    .map(ServiceAcceptanceAct::getAmount)
                    .map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (existingAmount.add(act.getAmount()).compareTo(order.getTotalAmount()) > 0) {
                throw new BusinessException("Cumulative service act amount exceeds service order amount");
            }
        }
    }

    private void rejectDuplicateStagePeriod(ServiceAcceptanceAct act, ServiceOrder order) {
        boolean duplicate = repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                        TenantContext.currentTenant(), order.getCompanyId(), order.getId())
                .stream()
                .filter(existing -> !Objects.equals(existing.getId(), act.getId()))
                .filter(existing -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(existing.getStatus()))
                .anyMatch(existing -> Objects.equals(normalizeStage(existing.getStage()), act.getStage())
                        && Objects.equals(existing.getPeriodStart(), act.getPeriodStart())
                        && Objects.equals(existing.getPeriodEnd(), act.getPeriodEnd()));
        if (duplicate) {
            throw new BusinessException("Duplicate service act stage and period is not allowed");
        }
    }

    private String normalizeStage(String stage) {
        return stage == null || stage.isBlank()
                ? "UNSPECIFIED"
                : stage.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private void assertNoOpenClaims(ServiceAcceptanceAct act) {
        if (act.getServiceRequestId() != null && !claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
                TenantContext.currentTenant(), act.getServiceRequestId(), com.enterprise.mm.enums.ServiceClaimStatus.CLOSED.name()).isEmpty()) {
            throw new BusinessException("Cannot proceed with open service claims");
        }
        if (!claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse(
                TenantContext.currentTenant(), act.getId(), com.enterprise.mm.enums.ServiceClaimStatus.CLOSED.name()).isEmpty()) {
            throw new BusinessException("Cannot proceed with open service claims");
        }
        if (act.getServiceOrderId() != null && !claimRepository.findByTenantIdAndServiceOrderIdAndStatusNotAndIsDeletedFalse(
                TenantContext.currentTenant(), act.getServiceOrderId(), com.enterprise.mm.enums.ServiceClaimStatus.CLOSED.name()).isEmpty()) {
            throw new BusinessException("Cannot proceed with open service claims");
        }
    }

    private ServiceOrder loadOrder(UUID serviceOrderId) {
        if (serviceOrderId == null) {
            throw new BusinessException("serviceOrderId is required");
        }
        return serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), serviceOrderId)
                .filter(order -> com.enterprise.mm.security.AccessScope.canAccessCompany(order.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Service order not found"));
    }

    private ServiceOrder lockOrderForAcceptance(ServiceOrder visibleOrder) {
        return serviceOrderRepository.findByTenantIdAndIdForAcceptanceUpdate(
                        TenantContext.currentTenant(), visibleOrder.getId())
                .filter(locked -> Objects.equals(locked.getCompanyId(), visibleOrder.getCompanyId()))
                .filter(locked -> com.enterprise.mm.security.AccessScope.canAccessCompany(locked.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Service order not found"));
    }

    private void requireOrderAccess(ServiceOrder order) {
        if (!accessService.canAccessServiceOrder(order, loadRequest(order.getServiceRequestId()))) {
            throw new BusinessException("Service order not found");
        }
    }

    private ServiceOrder loadOrderOrNull(UUID serviceOrderId) {
        if (serviceOrderId == null) {
            return null;
        }
        return serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), serviceOrderId)
                .filter(order -> com.enterprise.mm.security.AccessScope.canAccessCompany(order.getCompanyId()))
                .orElse(null);
    }

    private ServiceRequest loadRequest(UUID serviceRequestId) {
        if (serviceRequestId == null) {
            return null;
        }
        return serviceRequestRepository.findByIdAndIsDeletedFalse(serviceRequestId)
                .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
                .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
                .orElse(null);
    }

    private void updateRequestAfterAct(ServiceAcceptanceAct act, ServiceRequestStatus status) {
        if (act.getServiceRequestId() == null) {
            return;
        }
        ServiceRequest request = serviceRequestRepository.findByIdAndIsDeletedFalse(act.getServiceRequestId()).orElse(null);
        if (request != null) {
            List<ServiceAcceptanceAct> acts = repository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), request.getCompanyId(), request.getId());
            if (acts.isEmpty()) {
                acts = List.of(act);
            }
            List<ServiceAcceptanceAct> effectiveActs = acts.stream()
                    .filter(item -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(item.getStatus()))
                    .toList();
            List<ServiceAcceptanceAct> financialActs = effectiveActs.stream()
                    .filter(item -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(item.getStatus()))
                    .toList();
            BigDecimal cumulativeAmount = financialActs.stream()
                    .map(ServiceAcceptanceAct::getAmount)
                    .map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean allActsPaid = !financialActs.isEmpty() && financialActs.stream().allMatch(item ->
                    ServiceAcceptanceActStatus.PAID.name().equals(item.getStatus())
                            && trimToNull(item.getPaymentReference()) != null);
            boolean hasActiveReview = effectiveActs.stream()
                    .anyMatch(item -> ServiceOrderAcceptanceLifecycleService.isActiveReviewActStatus(item.getStatus()));
            boolean allOrdersCovered = allServiceOrdersCovered(request, financialActs);
            boolean coverageComplete = acceptedCoverageComplete(request, financialActs);
            boolean allExecutionsAccepted = allRequiredExecutionsAccepted(request.getId());
            request.setActualAmount(cumulativeAmount);
            if (!hasActiveReview && allActsPaid && allOrdersCovered && coverageComplete && allExecutionsAccepted) {
                request.setStatus(ServiceRequestStatus.PAID.name());
                releaseUnusedReservation(request, act);
            } else {
                request.setStatus(ServiceRequestStatus.SENT_TO_PAYMENT.name());
            }
            serviceRequestRepository.save(request);
        }
    }

    private boolean allServiceOrdersCovered(ServiceRequest request, List<ServiceAcceptanceAct> acts) {
        List<ServiceOrder> orders = serviceOrderRepository
                .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                        TenantContext.currentTenant(), request.getCompanyId(), request.getId());
        if (orders.isEmpty()) {
            return true;
        }
        Set<UUID> coveredOrderIds = acts.stream()
                .map(ServiceAcceptanceAct::getServiceOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return orders.stream().allMatch(order -> coveredOrderIds.contains(order.getId()));
    }

    private boolean acceptedCoverageComplete(ServiceRequest request, List<ServiceAcceptanceAct> acts) {
        String mode = request.getAcceptanceMode() == null || request.getAcceptanceMode().isBlank()
                ? "AMOUNT" : request.getAcceptanceMode().trim().toUpperCase(java.util.Locale.ROOT);
        if ("QUANTITY".equals(mode)) {
            return request.getOrderedQuantity() != null
                    && request.getOrderedQuantity().signum() > 0
                    && nvl(request.getAcceptedQuantity()).compareTo(request.getOrderedQuantity()) >= 0
                    && nvl(request.getRejectedQuantity()).signum() == 0;
        }
        BigDecimal ordered = request.getOrderedAmount();
        if (ordered == null || ordered.signum() <= 0) {
            ordered = nvl(request.getApprovedAmount()).signum() > 0
                    ? request.getApprovedAmount() : request.getEstimatedAmount();
        }
        BigDecimal rejected = request.getRejectedAmount() == null
                ? nvl(request.getRejectedVolume()) : request.getRejectedAmount();
        if (ordered == null || ordered.signum() <= 0 || nvl(rejected).signum() > 0) {
            return false;
        }
        BigDecimal actCoverage = acts.stream()
                .filter(item -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(item.getStatus()))
                .map(ServiceAcceptanceAct::getAmount)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return actCoverage.compareTo(ordered) >= 0;
    }

    private boolean allRequiredExecutionsAccepted(UUID serviceRequestId) {
        List<ServiceExecution> executions = executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), serviceRequestId);
        return !executions.isEmpty() && executions.stream().allMatch(item -> List.of(
                ServiceExecutionStatus.ACCEPTED.name(),
                ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
                ServiceExecutionStatus.CANCELLED.name()
        ).contains(item.getStatus()));
    }

    private void releaseUnusedReservation(ServiceRequest request, ServiceAcceptanceAct sourceAct) {
        BigDecimal remaining = nvl(request.getBudgetReservedAmount());
        if (request.getBudgetItemId() == null || remaining.signum() <= 0) {
            return;
        }
        budgetItemService.release(request.getBudgetItemId(), remaining, "ServiceAcceptanceAct " + sourceAct.getCode());
        request.setBudgetReservedAmount(BigDecimal.ZERO);
        request.setBudgetReservedAt(null);
        request.setBudgetReleasedAt(LocalDateTime.now());
    }

    private void syncServicePoLines(ServiceAcceptanceAct act, ServiceOrder order, String status) {
        if (order.getPurchaseOrderId() == null) {
            return;
        }
        purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), order.getPurchaseOrderId()).stream()
                .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
                .filter(item -> item.getServiceActId() == null || Objects.equals(item.getServiceActId(), act.getId()))
                .forEach(item -> {
                    item.setServiceOrderId(order.getId());
                    item.setServiceActId(act.getId());
                    item.setStatus(status);
                    purchaseOrderItemRepository.save(item);
                });
    }

    /**
     * Mirrors {@link WorkCompletionActService} paid-coverage completion:
     * SERVICE PO stays PARTIALLY_COMPLETED until paid acts cover ordered amount, then becomes COMPLETED.
     */
    private void recomputePurchaseOrderCompletion(UUID purchaseOrderId) {
        if (purchaseOrderId == null) {
            return;
        }
        PurchaseOrder order = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId).orElse(null);
        if (order == null) {
            return;
        }
        List<PurchaseOrderItem> serviceItems = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
                .stream()
                .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
                .toList();
        if (serviceItems.isEmpty()) {
            return;
        }

        BigDecimal paidAmount = repository
                .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
                        TenantContext.currentTenant(),
                        purchaseOrderId,
                        PAID_COVERAGE_STATUSES
                )
                .stream()
                .map(ServiceAcceptanceAct::getAmount)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal orderedAmount = serviceItems.stream()
                .map(item -> nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (orderedAmount.signum() <= 0) {
            orderedAmount = nvl(order.getTotalAmount());
        }

        boolean allComplete = orderedAmount.signum() > 0 && paidAmount.compareTo(orderedAmount) >= 0;
        boolean anyCoverage = paidAmount.signum() > 0;

        for (PurchaseOrderItem item : serviceItems) {
            if (allComplete) {
                item.setStatus("COMPLETED");
            } else if (anyCoverage) {
                item.setStatus("PARTIALLY_COMPLETED");
            }
            purchaseOrderItemRepository.save(item);
        }

        if (allComplete) {
            order.setStatus(PurchaseOrderStatus.COMPLETED.name());
            purchaseOrderRepository.save(order);
        } else if (anyCoverage
                && !PurchaseOrderStatus.COMPLETED.name().equals(order.getStatus())) {
            order.setStatus(PurchaseOrderStatus.PARTIALLY_COMPLETED.name());
            purchaseOrderRepository.save(order);
        }
    }

    private void consumeFinancialsOnce(ServiceAcceptanceAct act, ServiceOrder order) {
        if (act.getContractConsumedAt() == null && order.getContractId() != null && nvl(act.getAmount()).signum() > 0) {
            contractService.consumeLimit(order.getContractId(), nvl(act.getAmount()), true, "ServiceAcceptanceAct " + act.getCode());
            act.setContractConsumedAt(LocalDateTime.now());
        }
        if (act.getBudgetConsumedAt() == null) {
            List<com.enterprise.mm.entity.PurchaseOrderItem> serviceItems = List.of();
            if (order.getPurchaseOrderId() != null) {
                syncServicePoLines(act, order, "SERVICE_ACT_ACCEPTED");
                serviceItems = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), order.getPurchaseOrderId()).stream()
                        .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
                        .filter(item -> Objects.equals(item.getServiceActId(), act.getId()))
                        .toList();
            }
            if (!serviceItems.isEmpty()) {
                serviceItems.stream()
                        .filter(item -> item.getBudgetItemId() != null)
                        .forEach(item -> budgetItemService.consume(
                                item.getBudgetItemId(),
                                nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice())),
                                "ServiceAcceptanceAct " + act.getCode()
                        ));
            } else {
                consumeDirectRequestBudget(order, act);
            }
            act.setBudgetConsumedAt(LocalDateTime.now());
        }
    }

    private void emitPaymentEvents(ServiceAcceptanceAct saved, ServiceOrder order) {
        integrationEventService.emit("FI.SERVICE_ACCEPTED", "FI", saved.getId(), Map.of(
                "serviceOrderId", order.getId(),
                "acceptanceActId", saved.getId(),
                "amount", nvl(saved.getAmount()),
                "currency", currency(order)
        ));
        integrationEventService.emit("EDO.SERVICE_ACT_ACCEPTED", "EDO", saved.getId(), Map.of(
                "serviceOrderId", order.getId(),
                "acceptanceActId", saved.getId()
        ));
    }

    private void validateRequiredAttachments(UUID serviceActId) {
        List<String> attachmentTypes = attachmentService.list(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, serviceActId).stream()
                .map(item -> item.getAttachmentType() == null ? "DOCUMENT" : item.getAttachmentType())
                .collect(Collectors.toList());
        if (attachmentTypes.isEmpty()) {
            throw new BusinessException("At least one attachment is required before send-to-payment");
        }
        if (!attachmentTypes.contains("ACT")) {
            throw new BusinessException("ACT attachment is required before send-to-payment");
        }
    }

    private void validateReviewedActAttachment(ServiceAcceptanceAct act) {
        if (!attachmentService.hasAttachment(
                ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
                act.getId(),
                "ACT",
                act.getCompanyId()
        )) {
            throw new BusinessException("ACT attachment is required before document review");
        }
    }

    private void consumeDirectRequestBudget(ServiceOrder order, ServiceAcceptanceAct act) {
        ServiceRequest request = loadRequest(order.getServiceRequestId());
        if (request == null || request.getBudgetItemId() == null) {
            return;
        }
        BigDecimal reserved = nvl(request.getBudgetReservedAmount());
        if (reserved.signum() <= 0) {
            return;
        }
        BigDecimal consumed = reserved.min(nvl(act.getAmount()));
        if (consumed.signum() <= 0) {
            return;
        }
        budgetItemService.consume(request.getBudgetItemId(), consumed, "ServiceAcceptanceAct " + act.getCode());
        BigDecimal remaining = reserved.subtract(consumed);
        request.setBudgetReservedAmount(remaining);
        if (remaining.signum() == 0) {
            request.setBudgetReservedAt(null);
            request.setBudgetConsumedAt(LocalDateTime.now());
            request.setBudgetReleasedAt(null);
        } else {
            request.setBudgetConsumedAt(null);
        }
        serviceRequestRepository.save(request);
    }

    private ServiceAcceptanceAct lockedEntity(UUID id) {
        ServiceAcceptanceAct entity = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
                || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        if (!accessService.canAccessServiceAcceptanceAct(entity, loadRequest(entity.getServiceRequestId()), loadOrderOrNull(entity.getServiceOrderId()))) {
            throw new NotFoundException("ServiceAcceptanceAct not found: " + id);
        }
        return entity;
    }

    private BigDecimal amount(ServiceOrder order) {
        return nvl(order.getTotalAmount());
    }

    private String currency(ServiceOrder order) {
        return order.getCurrencyCode() == null || order.getCurrencyCode().isBlank() ? "UZS" : order.getCurrencyCode();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected boolean companyScoped() {
        return true;
    }


    @Transactional(readOnly = true)
    public ServiceAcceptanceActPrefillResponse getPrefillData(UUID serviceOrderId) {
        ServiceOrder order = loadOrder(serviceOrderId);
        requireOrderAccess(order);

        ServiceRequest request = order.getServiceRequest();

        DemandPlan demandPlan = null;
        if (order.getPurchaseOrder() != null
                && order.getPurchaseOrder().getPurchaseRequest() != null) {
            demandPlan = order.getPurchaseOrder().getPurchaseRequest().getDemandPlan();
        } else if (request != null
                && request.getPurchaseOrder() != null
                && request.getPurchaseOrder().getPurchaseRequest() != null) {
            demandPlan = request.getPurchaseOrder().getPurchaseRequest().getDemandPlan();
        }

        String sourceName = demandPlan != null ? demandPlan.getName() : (order.getName() != null ? order.getName() : order.getCode());
        String actName = com.enterprise.mm.util.BusinessNameGenerator.workflowDocumentName("Service Acceptance Act", sourceName);

        BigDecimal amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;

        LocalDate start = request != null ? request.getPlannedStartDate() : null;
        LocalDate end = request != null ? request.getPlannedEndDate() : null;

        if (start == null && demandPlan != null) {
            start = demandPlan.getPeriodStart();
        }
        if (end == null && demandPlan != null) {
            end = demandPlan.getPeriodEnd();
        }

        return ServiceAcceptanceActPrefillResponse.builder()
                .serviceOrderId(order.getId())
                .serviceOrderName(order.getName() != null ? order.getName() : order.getCode())
                .serviceRequestId(request != null ? request.getId() : null)
                .serviceRequestName(request != null ? (request.getName() != null ? request.getName() : request.getCode()) : null)
                .name(actName)
                .stage("FINAL")
                .periodStart(start)
                .periodEnd(end)
                .amount(amount)
                .vatRate(BigDecimal.ZERO)
                .vatAmount(BigDecimal.ZERO)
                .purchaseOrderId(order.getPurchaseOrderId())
                .contractId(order.getContractId())
                .currencyCode(order.getCurrencyCode())
                .build();
    }
}
