package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.WorkCompletionActMapper;
import com.enterprise.mm.payload.WorkCompletionActCreateRequest;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.payload.WorkCompletionActSearchRequest;
import com.enterprise.mm.payload.WorkCompletionActUpdateRequest;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.WorkCompletionActApprovalAdapter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "get"})
public class WorkCompletionActService {
    private static final String ENTITY_NAME = "WorkCompletionAct";
    private static final List<String> CUMULATIVE_STATUSES = List.of(
        WorkCompletionActStatus.DRAFT.name(),
        WorkCompletionActStatus.APPROVAL_PENDING.name(),
        WorkCompletionActStatus.APPROVED.name(),
        WorkCompletionActStatus.SENT_TO_PAYMENT.name(),
        WorkCompletionActStatus.PAID.name()
    );
    private static final Set<String> EDITABLE_STATUSES = Set.of(
        WorkCompletionActStatus.DRAFT.name(),
        WorkCompletionActStatus.REJECTED.name()
    );
    private static final List<String> COMPLETED_COVERAGE_STATUSES = List.of(
        WorkCompletionActStatus.APPROVED.name(),
        WorkCompletionActStatus.SENT_TO_PAYMENT.name(),
        WorkCompletionActStatus.PAID.name()
    );
    private static final List<String> PAID_COVERAGE_STATUSES = List.of(
        WorkCompletionActStatus.PAID.name()
    );
    private static final Set<String> ELIGIBLE_PO_STATUSES = Set.of(
        PurchaseOrderStatus.SENT.name(),
        PurchaseOrderStatus.PARTIALLY_COMPLETED.name(),
        PurchaseOrderStatus.COMPLETED.name()
    );

    private final WorkCompletionActRepository repository;
    private final WorkCompletionActMapper mapper;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ContractRepository contractRepository;
    private final AuditService auditService;
    private final ContractService contractService;
    private final AttachmentService attachmentService;
    private final ObjectProvider<ApprovalEngineService> approvalEngineService;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ServiceClaimRepository serviceClaimRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceWorkflowAccessService accessService;
    private final ProcurementPayablesService procurementPayablesService;

    @Transactional(readOnly = true)
    public Page<WorkCompletionActResponse> search(WorkCompletionActSearchRequest request, Pageable pageable) {
        WorkCompletionActSearchRequest effective = request == null ? WorkCompletionActSearchRequest.empty() : request;
        SearchCriteria filter = SearchCriteria.of(effective);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var specification = ProcurementSearchSpecifications.build(
            WorkCompletionAct.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            effective
        );
        if (accessService.hasFullAccess()) {
            return repository.findAll(specification, pageable).map(mapper::toResponse);
        }
        Page<WorkCompletionAct> records = repository.findAll(specification, Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            this::canAccessAct,
            mapper::toResponse
        );
    }

    @Transactional(readOnly = true)
    public WorkCompletionActResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public WorkCompletionActResponse create(WorkCompletionActCreateRequest request) {
        if (request == null) {
            throw new BusinessException("Work completion act request is required");
        }
        WorkCompletionAct entity = mapCreate(request);
        entity.setStatus(WorkCompletionActStatus.DRAFT.name());
        applyIdentityDefaults(entity);
        validate(entity, null);
        WorkCompletionAct saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public WorkCompletionActResponse update(UUID id, WorkCompletionActUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("Work completion act request is required");
        }
        WorkCompletionAct entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        String originalStatus = entity.getStatus();
        if (!EDITABLE_STATUSES.contains(originalStatus)) {
            throw new BusinessException("Only DRAFT or REJECTED work completion acts may be edited");
        }
        String originalCode = entity.getCode();
        String originalName = entity.getName();
        try {
            mapper.updateEntity(entity, request);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid inspectionResult");
        }
        entity.setStatus(originalStatus);
        if (isBlank(entity.getCode())) {
            entity.setCode(originalCode);
        }
        String parentName = null;
        if (entity.getPurchaseOrderId() != null) {
            parentName = loadPurchaseOrder(entity.getPurchaseOrderId()).getName();
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
        ));
        validate(entity, id);
        WorkCompletionAct saved = repository.save(entity);
        auditService.log(ENTITY_NAME, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        WorkCompletionAct entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireDeletableStatus(entity, "deleted");
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        PurchaseOrder purchaseOrder = loadPurchaseOrder(purchaseOrderId);
        if (PurchaseOrderStatus.CLOSED.name().equals(purchaseOrder.getStatus())
            && COMPLETED_COVERAGE_STATUSES.contains(entity.getStatus())) {
            throw new BusinessException("Completed work act cannot be deleted after WORKS purchase order is closed");
        }
        boolean affectsCoverage = COMPLETED_COVERAGE_STATUSES.contains(entity.getStatus());
        entity.softDelete();
        repository.save(entity);
        if (affectsCoverage || !PurchaseOrderStatus.CLOSED.name().equals(purchaseOrder.getStatus())) {
            recomputeCompletion(purchaseOrderId);
        }
        auditService.log(ENTITY_NAME, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public WorkCompletionActResponse restore(UUID id) {
        WorkCompletionAct entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        assertInScope(entity);
        assertActAccess(entity);
        requireDeletableStatus(entity, "restored");
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        validate(entity, id);
        WorkCompletionAct saved = repository.save(entity);
        recomputeCompletion(saved.getPurchaseOrderId());
        auditService.log(ENTITY_NAME, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    private void requireDeletableStatus(WorkCompletionAct entity, String action) {
        if (!WorkCompletionActStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Work completion act in status " + entity.getStatus() + " cannot be " + action);
        }
    }

    @Transactional
    public WorkCompletionActResponse submitForApproval(UUID id) {
        WorkCompletionAct entity = findEntity(id);
        if (isOrphanApprovalPending(entity)) {
            entity.setStatus(WorkCompletionActStatus.DRAFT.name());
            repository.save(entity);
        }
        if (!EDITABLE_STATUSES.contains(entity.getStatus())) {
            throw new BusinessException("Work completion act must be DRAFT or REJECTED, current status is " + entity.getStatus());
        }
        validate(entity, id);
        resetRejectedApprovalInstance(entity);
        approvalEngineService.getObject().start(WorkCompletionActApprovalAdapter.MODULE_KEY, id);
        return mapper.toResponse(findEntity(id));
    }

    private boolean isOrphanApprovalPending(WorkCompletionAct act) {
        if (act == null || !WorkCompletionActStatus.APPROVAL_PENDING.name().equals(act.getStatus())) {
            return false;
        }
        return approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                loadPurchaseOrder(act.getPurchaseOrderId()).getCompanyId(),
                WorkCompletionActApprovalAdapter.MODULE_KEY,
                act.getId()
            )
            .filter(instance -> instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING)
            .isEmpty();
    }

    @Transactional
    public void approveFromWorkflow(UUID id, String comment) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        if (COMPLETED_COVERAGE_STATUSES.contains(entity.getStatus())) {
            consumeContractOnce(entity);
            repository.save(entity);
            if (!PurchaseOrderStatus.CLOSED.name().equals(loadPurchaseOrder(entity.getPurchaseOrderId()).getStatus())) {
                recomputeCompletion(entity.getPurchaseOrderId());
            }
            return;
        }
        if (!WorkCompletionActStatus.APPROVAL_PENDING.name().equals(entity.getStatus())) {
            throw new BusinessException("Work completion act must be APPROVAL_PENDING, current status is " + entity.getStatus());
        }
        validateApprovalReadiness(entity);
        consumeContractOnce(entity);
        if (entity.getAcceptedAt() == null) {
            entity.setAcceptedAt(LocalDateTime.now());
        }
        if (isBlank(entity.getAcceptedBy())) {
            entity.setAcceptedBy(currentActor());
        }
        entity.setStatus(WorkCompletionActStatus.APPROVED.name());
        WorkCompletionAct saved = repository.save(entity);
        recomputeCompletion(saved.getPurchaseOrderId());
        auditService.log(ENTITY_NAME, id, "APPROVE", comment, null, null);
    }

    @Transactional
    public void rejectFromWorkflow(UUID id, String comment) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        if (!WorkCompletionActStatus.APPROVAL_PENDING.name().equals(entity.getStatus())) {
            throw new BusinessException("Work completion act must be APPROVAL_PENDING, current status is " + entity.getStatus());
        }
        entity.setStatus(WorkCompletionActStatus.REJECTED.name());
        repository.save(entity);
        recomputeCompletion(entity.getPurchaseOrderId());
        auditService.log(ENTITY_NAME, id, "REJECT", comment, null, null);
    }

    @Transactional
    public WorkCompletionActResponse sendToPayment(UUID id, String paymentReference) {
        return sendToPayment(id, paymentReference, null);
    }

    @Transactional
    public WorkCompletionActResponse sendToPayment(UUID id, String paymentReference, String comment) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        return sendToPayment(entity, paymentReference, comment);
    }

    @Transactional
    public WorkCompletionActResponse sendToPayment(
        UUID id,
        String paymentReference,
        String comment,
        Long expectedVersion
    ) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        requireExpectedVersion(entity, expectedVersion);
        return sendToPayment(entity, paymentReference, comment);
    }

    private WorkCompletionActResponse sendToPayment(
        WorkCompletionAct entity,
        String paymentReference,
        String comment
    ) {
        if (!WorkCompletionActStatus.APPROVED.name().equals(entity.getStatus())) {
            throw new BusinessException("Only APPROVED work completion act can be sent to payment");
        }
        assertNoOpenClaims(entity);
        procurementPayablesService.requirePayableInvoice(entity.getPurchaseOrderId(), entity.getAmount());
        entity.setStatus(WorkCompletionActStatus.SENT_TO_PAYMENT.name());
        entity.setSentToPaymentAt(LocalDateTime.now());
        if (!isBlank(paymentReference)) {
            entity.setPaymentReference(paymentReference);
        }
        WorkCompletionAct saved = repository.save(entity);
        procurementPayablesService.submitSourceDocumentPayment(
            saved.getPurchaseOrderId(),
            saved.getId(),
            ProcurementSourceDocumentType.WORK_COMPLETION_ACT,
            saved.getAmount(),
            isBlank(paymentReference) ? null : paymentReference.trim(),
            comment
        );
        recomputeCompletion(saved.getPurchaseOrderId());
        auditService.log(ENTITY_NAME, entity.getId(), "SEND_TO_PAYMENT", firstNonBlank(comment, paymentReference), null, null);
        return mapper.toResponse(saved);
    }

    private void assertNoOpenClaims(WorkCompletionAct act) {
        String tenant = TenantContext.currentTenant();
        String closed = com.enterprise.mm.enums.ServiceClaimStatus.CLOSED.name();
        if (!serviceClaimRepository.findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(
            tenant, act.getPurchaseOrderItemId(), closed).isEmpty()
            || !serviceClaimRepository.findByTenantIdAndWorkCompletionActIdAndStatusNotAndIsDeletedFalse(
                tenant, act.getId(), closed).isEmpty()) {
            throw new BusinessException("Cannot proceed with open service claims");
        }
    }

    @Transactional
    public WorkCompletionActResponse markPaid(UUID id, String paymentReference) {
        return markPaid(id, paymentReference, null);
    }

    @Transactional
    public WorkCompletionActResponse markPaid(UUID id, String paymentReference, String comment) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        return markPaid(entity, paymentReference, comment);
    }

    @Transactional
    public WorkCompletionActResponse markPaid(
        UUID id,
        String paymentReference,
        String comment,
        Long expectedVersion
    ) {
        WorkCompletionAct entity = findEntityForUpdate(id);
        requireExpectedVersion(entity, expectedVersion);
        return markPaid(entity, paymentReference, comment);
    }

    private WorkCompletionActResponse markPaid(
        WorkCompletionAct entity,
        String paymentReference,
        String comment
    ) {
        if (!WorkCompletionActStatus.SENT_TO_PAYMENT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only SENT_TO_PAYMENT work completion act can be marked paid");
        }
        assertNoOpenClaims(entity);
        String externalReference = !isBlank(paymentReference) ? paymentReference.trim()
            : !isBlank(entity.getPaymentReference()) ? entity.getPaymentReference().trim() : null;
        if (externalReference == null) {
            throw new BusinessException("External Treasury payment reference is required before marking paid");
        }
        procurementPayablesService.markSourceDocumentPaymentPaid(
            entity.getId(),
            ProcurementSourceDocumentType.WORK_COMPLETION_ACT,
            externalReference,
            comment
        );
        entity.setStatus(WorkCompletionActStatus.PAID.name());
        entity.setPaidAt(LocalDateTime.now());
        entity.setPaymentReference(externalReference);
        WorkCompletionAct saved = repository.save(entity);
        recomputeCompletion(saved.getPurchaseOrderId());
        auditService.log(ENTITY_NAME, entity.getId(), "MARK_PAID", firstNonBlank(comment, paymentReference), null, null);
        return mapper.toResponse(saved);
    }

    private void requireExpectedVersion(WorkCompletionAct entity, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BusinessException("expectedVersion is required for work completion act payment commands");
        }
        if (!Objects.equals(entity.getVersion(), expectedVersion)) {
            throw new ConflictException(
                "Work completion act was changed by another user; refresh the workspace and retry"
            );
        }
    }

    protected WorkCompletionAct findEntity(UUID id) {
        WorkCompletionAct entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        assertInScope(entity);
        assertActAccess(entity);
        return entity;
    }

    private WorkCompletionAct findEntityForUpdate(UUID id) {
        WorkCompletionAct entity = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        assertInScope(entity);
        assertActAccess(entity);
        return entity;
    }

    private WorkCompletionAct mapCreate(WorkCompletionActCreateRequest request) {
        try {
            return mapper.toEntity(request);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid inspectionResult");
        }
    }

    private void applyIdentityDefaults(WorkCompletionAct entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (isBlank(entity.getCode())) {
            entity.setCode("WORK_COMPLETION_ACT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT));
        }
        String parentName = null;
        if (entity.getPurchaseOrderId() != null) {
            parentName = loadPurchaseOrder(entity.getPurchaseOrderId()).getName();
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), parentName
        ));
    }

    private void validate(WorkCompletionAct entity, UUID currentActId) {
        PurchaseOrder purchaseOrder = loadPurchaseOrder(entity.getPurchaseOrderId());
        entity.setCompanyId(purchaseOrder.getCompanyId());

        if (!ELIGIBLE_PO_STATUSES.contains(purchaseOrder.getStatus())) {
            throw new BusinessException("Work completion act requires a SENT, PARTIALLY_COMPLETED, or COMPLETED purchase order");
        }
        if (purchaseOrder.getRequestType() != RequestType.SERVICE) {
            throw new BusinessException("Work completion act requires a WORKS purchase order");
        }

        PurchaseOrderItem item = loadPurchaseOrderItemForUpdate(entity.getPurchaseOrderItemId());
        if (!Objects.equals(item.getPurchaseOrderId(), purchaseOrder.getId())) {
            throw new BusinessException("Purchase order item does not belong to purchase order");
        }
        if (item.getLineType() != ProcurementLineType.WORK) {
            throw new BusinessException("Work completion act requires a WORK purchase order item");
        }
        if (item.getQuantity() == null || item.getQuantity().signum() <= 0) {
            throw new BusinessException("PO line quantity must be positive");
        }

        ensureWorksContractLinked(entity, purchaseOrder);

        if (entity.getContractId() == null || !Objects.equals(entity.getContractId(), purchaseOrder.getContractId())) {
            throw new BusinessException("Work completion act contract must match purchase order contract");
        }
        loadContract(entity.getContractId());

        if (entity.getCompletedQuantity() == null || entity.getCompletedQuantity().signum() <= 0) {
            throw new BusinessException("completedQuantity must be positive");
        }
        BigDecimal cumulativeQuantity = repository.cumulativeQuantity(
            TenantContext.currentTenant(),
            item.getId(),
            CUMULATIVE_STATUSES,
            currentActId
        );
        if (cumulativeQuantity.add(entity.getCompletedQuantity()).compareTo(item.getQuantity()) > 0) {
            throw new BusinessException("Cumulative completed quantity exceeds PO line quantity");
        }
        if (entity.getAmount() == null || entity.getAmount().signum() <= 0) {
            throw new BusinessException("amount must be positive");
        }
        if (item.getUnitPrice() == null || item.getUnitPrice().signum() <= 0) {
            throw new BusinessException("PO line unit price must be positive to validate work completion act amount");
        }
        BigDecimal lineAmountCap = item.getQuantity()
            .multiply(item.getUnitPrice())
            .setScale(4, RoundingMode.HALF_UP);
        BigDecimal actAmount = entity.getAmount().setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedActAmount = entity.getCompletedQuantity()
            .multiply(item.getUnitPrice())
            .setScale(4, RoundingMode.HALF_UP);
        if (actAmount.compareTo(expectedActAmount) != 0) {
            throw new BusinessException(
                "Work completion act amount must equal completed quantity multiplied by PO line unit price"
            );
        }
        BigDecimal cumulativeAmount = nvl(repository.cumulativeAmount(
            TenantContext.currentTenant(),
            item.getId(),
            CUMULATIVE_STATUSES,
            currentActId
        )).setScale(4, RoundingMode.HALF_UP);
        if (cumulativeAmount.add(actAmount).compareTo(lineAmountCap) > 0) {
            throw new BusinessException("Cumulative work completion act amount exceeds PO line amount");
        }
        entity.setAmount(actAmount);
        if (entity.getPeriodStart() != null && entity.getPeriodEnd() != null
            && entity.getPeriodEnd().isBefore(entity.getPeriodStart())) {
            throw new BusinessException("periodEnd cannot precede periodStart");
        }
        if (entity.getInspectionResult() == WorkInspectionResult.PASSED_WITH_REMARKS && isBlank(entity.getRemarks())) {
            throw new BusinessException("remarks are required for PASSED_WITH_REMARKS inspection result");
        }

    }

    private void validateApprovalReadiness(WorkCompletionAct entity) {
        validate(entity, entity.getId());
        if (entity.getInspectionResult() == WorkInspectionResult.FAILED) {
            throw new BusinessException("FAILED inspection result cannot be approved");
        }
        boolean hasActAttachment = attachmentService.list("WORK_COMPLETION_ACT", entity.getId()).stream()
            .anyMatch(attachment -> "ACT".equals(attachment.getAttachmentType()));
        if (!hasActAttachment) {
            throw new BusinessException("ACT attachment is required before work completion approval");
        }
    }

    private void consumeContractOnce(WorkCompletionAct entity) {
        if (entity.getContractConsumedAt() != null) {
            return;
        }
        contractService.consumeLimit(entity.getContractId(), entity.getAmount(), false, "WorkCompletionAct " + entity.getCode());
        entity.setContractConsumedAt(LocalDateTime.now());
    }

    private void resetRejectedApprovalInstance(WorkCompletionAct entity) {
        if (!WorkCompletionActStatus.REJECTED.name().equals(entity.getStatus())) {
            return;
        }
        approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            loadPurchaseOrder(entity.getPurchaseOrderId()).getCompanyId(),
            WorkCompletionActApprovalAdapter.MODULE_KEY,
            entity.getId()
        ).filter(instance -> instance.getApprovalStatus() == ApprovalInstanceStatus.REJECTED)
            .ifPresent(instance -> {
                instance.setApprovalStatus(ApprovalInstanceStatus.DRAFT);
                instance.setStatus(ApprovalInstanceStatus.DRAFT.name());
                instance.setCurrentStepOrder(null);
                instance.setStartedAt(null);
                instance.setCompletedAt(null);
                approvalInstanceRepository.save(instance);
            });
    }

    private void recomputeCompletion(UUID purchaseOrderId) {
        PurchaseOrder order = loadPurchaseOrder(purchaseOrderId);
        List<PurchaseOrderItem> workItems = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.WORK)
            .toList();
        boolean anyCoverage = false;
        boolean allComplete = !workItems.isEmpty();
        for (PurchaseOrderItem item : workItems) {
            BigDecimal paidQuantity = repository.cumulativeQuantity(
                TenantContext.currentTenant(),
                item.getId(),
                PAID_COVERAGE_STATUSES,
                null
            );
            BigDecimal ordered = nvl(item.getQuantity());
            BigDecimal paidAmount = repository.cumulativeAmount(
                TenantContext.currentTenant(),
                item.getId(),
                PAID_COVERAGE_STATUSES,
                null
            );
            BigDecimal orderedAmount = ordered.multiply(nvl(item.getUnitPrice()));
            boolean quantityCovered = ordered.signum() > 0 && paidQuantity.compareTo(ordered) >= 0;
            boolean amountCovered = orderedAmount.signum() > 0 && paidAmount.compareTo(orderedAmount) >= 0;
            if (quantityCovered && amountCovered) {
                item.setStatus("COMPLETED");
            } else if (paidQuantity.signum() > 0 || paidAmount.signum() > 0) {
                item.setStatus("PARTIALLY_COMPLETED");
                allComplete = false;
            } else {
                item.setStatus("OPEN");
                allComplete = false;
            }
            anyCoverage = anyCoverage || paidQuantity.signum() > 0 || paidAmount.signum() > 0;
            purchaseOrderItemRepository.save(item);
        }
        if (allComplete) {
            order.setStatus(PurchaseOrderStatus.COMPLETED.name());
        } else if (anyCoverage) {
            order.setStatus(PurchaseOrderStatus.PARTIALLY_COMPLETED.name());
        } else if (PurchaseOrderStatus.PARTIALLY_COMPLETED.name().equals(order.getStatus())
            || PurchaseOrderStatus.COMPLETED.name().equals(order.getStatus())) {
            order.setStatus(PurchaseOrderStatus.SENT.name());
        }
        purchaseOrderRepository.save(order);
    }

    private void ensureWorksContractLinked(WorkCompletionAct entity, PurchaseOrder purchaseOrder) {
        if (entity.getContractId() == null && purchaseOrder.getContractId() != null) {
            entity.setContractId(purchaseOrder.getContractId());
            return;
        }
        if (entity.getContractId() != null && purchaseOrder.getContractId() == null) {
            UUID resolved = contractService.requireWorksContract(
                entity.getContractId(),
                purchaseOrder.getSupplierId(),
                purchaseOrder.getCurrencyCode()
            );
            purchaseOrder.setContractId(resolved);
            entity.setContractId(resolved);
            purchaseOrderRepository.save(purchaseOrder);
            return;
        }
        if (entity.getContractId() == null && purchaseOrder.getContractId() == null) {
            UUID resolved = contractService.requireWorksContract(
                null,
                purchaseOrder.getSupplierId(),
                purchaseOrder.getCurrencyCode()
            );
            entity.setContractId(resolved);
            purchaseOrder.setContractId(resolved);
            purchaseOrderRepository.save(purchaseOrder);
        }
    }

    private PurchaseOrder loadPurchaseOrder(UUID id) {
        if (id == null) {
            throw new BusinessException("purchaseOrderId is required");
        }
        PurchaseOrder entity = purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + id));
        assertInScope(entity, "PurchaseOrder not found: " + id);
        if (!accessService.canAccessPurchaseOrder(entity)) {
            throw new NotFoundException("PurchaseOrder not found: " + id);
        }
        return entity;
    }

    private PurchaseOrderItem loadPurchaseOrderItemForUpdate(UUID id) {
        if (id == null) {
            throw new BusinessException("purchaseOrderItemId is required");
        }
        PurchaseOrderItem entity = purchaseOrderItemRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("PurchaseOrderItem not found: " + id));
        assertInScope(entity, "PurchaseOrderItem not found: " + id);
        return entity;
    }

    private Contract loadContract(UUID id) {
        Contract entity = contractRepository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Contract not found: " + id));
        assertInScope(entity, "Contract not found: " + id);
        return entity;
    }

    private void assertInScope(com.enterprise.mm.common.BusinessEntity entity) {
        assertInScope(entity, ENTITY_NAME + " not found: " + entity.getId());
    }

    private void assertInScope(com.enterprise.mm.common.BusinessEntity entity, String message) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(message);
        }
    }

    private void assertActAccess(WorkCompletionAct act) {
        if (!canAccessAct(act)) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + act.getId());
        }
    }

    private boolean canAccessAct(WorkCompletionAct act) {
        if (act == null || act.getPurchaseOrderId() == null || !inScope(act)) {
            return false;
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(act.getPurchaseOrderId())
            .filter(this::inScope)
            .filter(accessService::canAccessPurchaseOrder)
            .isPresent();
    }

    private boolean inScope(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && (!com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()));
    }

    private boolean companyScoped() {
        return true;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }
}
