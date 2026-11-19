package com.enterprise.mm.service;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceRequestRemark;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceRequestMapper;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.ServiceRequestSearchRequest;
import com.enterprise.mm.payload.ServiceRequestUpdateRequest;
import com.enterprise.mm.payload.ServicePartialAcceptRequest;
import com.enterprise.mm.payload.ServiceRemarkResponse;
import com.enterprise.mm.payload.ServiceRemarksRequest;
import com.enterprise.mm.payload.ServiceRemainingContractResponse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceRequestMilestoneRepository;
import com.enterprise.mm.repository.ServiceRequestRemarkRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ServiceRequestApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "get", "remainingContract", "findEntity"})
public class ServiceRequestService {
    private final ServiceRequestRepository repository;
    private final ServiceRequestMapper mapper;
    private final AuditService auditService;
    private final com.enterprise.mm.service.approval.ApprovalEngineService approvalEngineService;
    private final ServiceWorkflowNotificationService notificationService;
    private final ServiceReviewHistoryService historyService;
    private final ServiceClaimRepository claimRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceWorkflowAccessService accessService;
    private final BudgetItemService budgetItemService;
    private final ServiceRequestRemarkRepository remarkRepository;
    private final ContractRepository contractRepository;
    private final UserRepository userRepository;
    private final ServiceExecutionRepository executionRepository;
    private final ServiceAcceptanceActRepository acceptanceActRepository;
    private final ServiceOrderRepository serviceOrderRepository;
    private final AttachmentService attachmentService;
    private final DepartmentRepository departmentRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ServiceRequestMilestoneRepository milestoneRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceRequestService(
        ServiceRequestRepository repository,
        ServiceRequestMapper mapper,
        AuditService auditService,
        com.enterprise.mm.service.approval.ApprovalEngineService approvalEngineService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        ServiceClaimRepository claimRepository,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        BudgetItemService budgetItemService,
        ServiceRequestRemarkRepository remarkRepository,
        ContractRepository contractRepository,
        UserRepository userRepository,
        ServiceExecutionRepository executionRepository,
        ServiceAcceptanceActRepository acceptanceActRepository,
        ServiceOrderRepository serviceOrderRepository,
        AttachmentService attachmentService,
        DepartmentRepository departmentRepository,
        SupplierRepository supplierRepository,
        PurchaseOrderRepository purchaseOrderRepository
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.approvalEngineService = approvalEngineService;
        this.notificationService = notificationService;
        this.historyService = historyService;
        this.claimRepository = claimRepository;
        this.roleDataScopeService = roleDataScopeService;
        this.accessService = accessService;
        this.budgetItemService = budgetItemService;
        this.remarkRepository = remarkRepository;
        this.contractRepository = contractRepository;
        this.userRepository = userRepository;
        this.executionRepository = executionRepository;
        this.acceptanceActRepository = acceptanceActRepository;
        this.serviceOrderRepository = serviceOrderRepository;
        this.attachmentService = attachmentService;
        this.departmentRepository = departmentRepository;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public ServiceRequestService(
        ServiceRequestRepository repository,
        ServiceRequestMapper mapper,
        AuditService auditService,
        com.enterprise.mm.service.approval.ApprovalEngineService approvalEngineService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        ServiceClaimRepository claimRepository,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        BudgetItemService budgetItemService,
        ServiceRequestRemarkRepository remarkRepository,
        ContractRepository contractRepository,
        UserRepository userRepository,
        ServiceExecutionRepository executionRepository,
        ServiceAcceptanceActRepository acceptanceActRepository,
        ServiceOrderRepository serviceOrderRepository,
        AttachmentService attachmentService
    ) {
        this(
            repository,
            mapper,
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService,
            remarkRepository,
            contractRepository,
            userRepository,
            executionRepository,
            acceptanceActRepository,
            serviceOrderRepository,
            attachmentService,
            null,
            null,
            null
        );
    }

    public ServiceRequestService(
        ServiceRequestRepository repository,
        ServiceRequestMapper mapper,
        AuditService auditService,
        com.enterprise.mm.service.approval.ApprovalEngineService approvalEngineService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        ServiceClaimRepository claimRepository,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        BudgetItemService budgetItemService
    ) {
        this(
            repository,
            mapper,
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Transactional(readOnly = true)
    public Page<ServiceRequestResponse> search(ServiceRequestSearchRequest request, Pageable pageable) {
        if (accessService.hasFullAccess()) {
            return repository.findAll(spec(request), pageable).map(mapper::toResponse);
        }
        Page<ServiceRequest> records = repository.findAll(spec(request), Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            accessService::canAccessServiceRequest,
            mapper::toResponse
        );
    }

    @Transactional(readOnly = true)
    public ServiceRequestResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ServiceRequestResponse create(ServiceRequestCreateRequest request) {
        ServiceRequest entity = mapper.toEntity(request);
        beforeCreate(entity, hasExplicitAcceptanceContract(request));
        ServiceRequest saved = repository.save(entity);
        approvalEngineService.linkItem(ServiceRequestApprovalAdapter.MODULE_KEY, saved.getId(), saved.getCompanyId(), saved.getCode());
        auditService.log("ServiceRequest", saved.getId(), "CREATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse update(UUID id, ServiceRequestUpdateRequest request) {
        ServiceRequest entity = findEntity(id);
        if (!List.of(ServiceRequestStatus.DRAFT.name(), ServiceRequestStatus.RETURNED.name()).contains(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or RETURNED service request can be edited");
        }
        BigDecimal previousReservedAmount = nvl(entity.getBudgetReservedAmount());
        UUID previousBudgetItemId = entity.getBudgetItemId();
        String previousAcceptanceMode = acceptanceMode(entity);
        String requestedAcceptanceMode = request.getAcceptanceMode() == null || request.getAcceptanceMode().isBlank()
            ? previousAcceptanceMode
            : normalize(request.getAcceptanceMode(), previousAcceptanceMode);
        if (!List.of("AMOUNT", "QUANTITY").contains(requestedAcceptanceMode)) {
            throw new BusinessException("acceptanceMode must be AMOUNT or QUANTITY");
        }
        boolean modeChanged = !previousAcceptanceMode.equals(requestedAcceptanceMode);
        if (modeChanged && hasAcceptanceProgress(entity)) {
            throw new BusinessException("acceptanceMode cannot change after acceptance has started");
        }
        mapper.updateEntity(entity, request);
        entity.setAcceptanceMode(requestedAcceptanceMode);
        if (modeChanged) {
            clearInactiveAcceptanceBasis(entity, requestedAcceptanceMode);
        }
        beforeUpdate(entity);
        adjustDraftBudgetReservation(entity, previousBudgetItemId, previousReservedAmount);
        ServiceRequest saved = repository.save(entity);
        auditService.log("ServiceRequest", id, "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse submit(UUID id) {
        ServiceRequest request = findEntity(id);
        validateAcceptanceForProgress(request);
        approvalEngineService.start(ServiceRequestApprovalAdapter.MODULE_KEY, id);
        notificationService.approvalRequired("DEPARTMENT_HEAD", request.getCode());
        historyService.record(id, null, "SERVICE_REQUEST", "SUBMITTED", null);
        return mapper.toResponse(repository.findByIdAndIsDeletedFalse(id).orElseThrow());
    }

    @Transactional
    public ServiceRequestResponse rollback(UUID id, ServiceActionRequest action) {
        ServiceRequest request = findEntity(id);
        if (!ServiceRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())
            && !ServiceRequestStatus.APPROVED.name().equals(request.getStatus())) {
            throw new BusinessException("Only pending or approved service request can be rolled back");
        }
        String comment = action == null ? null : action.comment();
        approvalEngineService.resetForResubmission(ServiceRequestApprovalAdapter.MODULE_KEY, id, comment);
        releaseReservedBudget(request, "ServiceRequest " + request.getCode());
        request.setStatus(ServiceRequestStatus.RETURNED.name());
        historyService.record(id, null, "SERVICE_REQUEST", "RETURNED", comment);
        auditService.log("ServiceRequest", id, "ROLLBACK", request.getCode(), null, null);
        return mapper.toResponse(repository.save(request));
    }

    @Transactional
    public ServiceRequestResponse close(UUID id, ServiceActionRequest action) {
        ServiceRequest request = findEntity(id);
        if (!claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
            TenantContext.currentTenant(), id, com.enterprise.mm.enums.ServiceClaimStatus.CLOSED.name()).isEmpty()) {
            throw new BusinessException("Cannot close service request with open claims");
        }
        requireCloseReadiness(request);
        request.setStatus(ServiceRequestStatus.CLOSED.name());
        historyService.record(id, null, "CLOSURE", "CLOSED", action == null ? null : action.comment());
        auditService.log("ServiceRequest", id, "CLOSE", request.getCode(), null, null);
        return mapper.toResponse(repository.save(request));
    }

    @Transactional
    public ServiceRequestResponse partialAccept(UUID id, ServicePartialAcceptRequest request) {
        return partialAccept(id, request, null);
    }

    @Transactional
    public ServiceRequestResponse partialAccept(UUID id, ServicePartialAcceptRequest request, Long expectedVersion) {
        ServiceRequest entity = findEntity(id);
        if (milestoneRepository != null && milestoneRepository
            .existsByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getId()
            )) {
            throw new BusinessException(
                "Milestone-backed requests must be accepted through the milestone acceptance endpoint"
            );
        }
        requireVersion(entity.getVersion(), expectedVersion);
        if (!List.of(
            ServiceRequestStatus.WAITING_ACCEPTANCE.name(),
            ServiceRequestStatus.PARTIALLY_ACCEPTED.name()
        ).contains(entity.getStatus())) {
            throw new BusinessException("Service request is not ready for acceptance");
        }
        requireAcceptedExecutionsForRequestAcceptance(entity);
        BigDecimal accepted = nvl(request.acceptedVolume());
        BigDecimal rejected = nvl(request.rejectedVolume());
        if (accepted.add(rejected).signum() <= 0) {
            throw new BusinessException("Accepted or rejected volume must be positive");
        }
        String mode = acceptanceMode(entity);
        BigDecimal ordered;
        BigDecimal nextAccepted;
        BigDecimal nextRejected;
        if ("QUANTITY".equals(mode)) {
            ordered = requiredPositive(entity.getOrderedQuantity(), "orderedQuantity");
            nextAccepted = nvl(entity.getAcceptedQuantity()).add(accepted);
            BigDecimal correctedRejection = accepted.min(nvl(entity.getRejectedQuantity()));
            nextRejected = nvl(entity.getRejectedQuantity()).subtract(correctedRejection).add(rejected);
            rejectOverAcceptance(nextAccepted, nextRejected, ordered, "quantity");
            entity.setAcceptedQuantity(nextAccepted);
            entity.setRejectedQuantity(nextRejected);
        } else {
            ordered = orderedAmount(entity);
            nextAccepted = nvl(entity.getAcceptedAmount()).add(accepted);
            BigDecimal correctedRejection = accepted.min(nvl(entity.getRejectedAmount()));
            nextRejected = nvl(entity.getRejectedAmount()).subtract(correctedRejection).add(rejected);
            rejectOverAcceptance(nextAccepted, nextRejected, ordered, "amount");
            entity.setAcceptedAmount(nextAccepted);
            entity.setRejectedAmount(nextRejected);
            entity.setActualAmount(nextAccepted);
        }
        entity.setAcceptedVolume(nextAccepted);
        entity.setRejectedVolume(nextRejected);
        entity.setAcceptanceRemarks(request.remarks());
        entity.setStatus(
            ordered.signum() > 0 && nextAccepted.compareTo(ordered) >= 0 && nextRejected.signum() == 0
                ? ServiceRequestStatus.ACCEPTED.name()
                : ServiceRequestStatus.PARTIALLY_ACCEPTED.name()
        );
        ServiceRequest saved = repository.save(entity);
        historyService.record(id, null, "SERVICE_ACCEPTANCE", saved.getStatus(), request.remarks());
        auditService.log("ServiceRequest", id, "PARTIAL_ACCEPT", "accepted=" + accepted + ", rejected=" + rejected, null, null);
        return mapper.toResponse(saved);
    }

    private void requireAcceptedExecutionsForRequestAcceptance(ServiceRequest request) {
        if (executionRepository == null) {
            return;
        }
        List<com.enterprise.mm.entity.ServiceExecution> executions = executionRepository
            .findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TenantContext.currentTenant(), request.getId());
        if (executions.isEmpty()) {
            // Compatibility policy for direct/no-execution service requests already routed to WAITING_ACCEPTANCE.
            return;
        }
        boolean atLeastOneAccepted = executions.stream().anyMatch(item -> List.of(
            com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED.name(),
            com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name()
        ).contains(item.getStatus()));
        boolean everyExecutionFinished = executions.stream().allMatch(item -> List.of(
            com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED.name(),
            com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
            com.enterprise.mm.enums.ServiceExecutionStatus.CANCELLED.name()
        ).contains(item.getStatus()));
        if (!atLeastOneAccepted || !everyExecutionFinished) {
            throw new BusinessException("Service request acceptance requires all active executions to be accepted or cancelled");
        }
    }

    private void requireVersion(Long actualVersion, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(actualVersion, expectedVersion)) {
            throw new com.enterprise.mm.error.ConflictException(
                "Service request was changed by another user; refresh the workspace and retry"
            );
        }
    }

    @Transactional
    public List<ServiceRemarkResponse> submitRemarks(UUID id, ServiceRemarksRequest request) {
        ServiceRequest entity = findEntity(id);
        List<ServiceRemarkResponse> result = new ArrayList<>();
        for (ServiceRemarksRequest.Remark input : request.remarks()) {
            if (input.assignedTo() != null) {
                userRepository.findByIdAndIsDeletedFalse(input.assignedTo())
                    .filter(user -> TenantContext.currentTenant().equals(user.getTenantId()))
                    .filter(user -> CompanyContext.currentCompany().equals(user.getCompanyId()))
                    .orElseThrow(() -> new NotFoundException("Assigned user not found: " + input.assignedTo()));
            }
            ServiceRequestRemark remark = new ServiceRequestRemark();
            remark.setCode("SR-REM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            remark.setName(BusinessNameGenerator.workflowDocumentName("ServiceRequestRemark", entity.getName()));
            remark.setStatus("OPEN");
            remark.setServiceRequestId(entity.getId());
            remark.setSeverity(normalize(input.severity(), "NORMAL"));
            remark.setRemarkDescription(input.description());
            remark.setDueDate(input.dueDate());
            remark.setAssignedTo(input.assignedTo());
            ServiceRequestRemark saved = remarkRepository.save(remark);
            result.add(toRemarkResponse(saved));
        }
        historyService.record(id, null, "SERVICE_REMARKS", "SUBMITTED", "count=" + result.size());
        auditService.log("ServiceRequest", id, "SUBMIT_REMARKS", "count=" + result.size(), null, null);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public ServiceRemainingContractResponse remainingContract(UUID id) {
        ServiceRequest entity = findEntity(id);
        if (entity.getContractId() == null) {
            throw new NotFoundException("Service request has no linked contract");
        }
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(entity.getContractId())
            .filter(record -> TenantContext.currentTenant().equals(record.getTenantId()))
            .filter(record -> com.enterprise.mm.security.AccessScope.canAccessCompany(record.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Contract not found: " + entity.getContractId()));
        BigDecimal ordered = nvl(contract.getLimitAmount());
        BigDecimal accepted = nvl(contract.getServiceUsedAmount());
        return new ServiceRemainingContractResponse(
            ordered,
            accepted,
            ordered.subtract(accepted).max(BigDecimal.ZERO),
            contract.getCurrencyCode()
        );
    }

    private ServiceRemarkResponse toRemarkResponse(ServiceRequestRemark remark) {
        return new ServiceRemarkResponse(
            remark.getId(),
            remark.getSeverity(),
            remark.getRemarkDescription(),
            remark.getDueDate(),
            remark.getAssignedTo(),
            remark.getStatus(),
            remark.getCreatedAt()
        );
    }

    @Transactional
    public void softDelete(UUID id) {
        ServiceRequest entity = findEntity(id);
        requireDraftWithoutEffects(entity, "deleted");
        entity.softDelete();
        repository.save(entity);
        auditService.log("ServiceRequest", id, "SOFT_DELETE", entity.getCode(), null, null);
    }

    @Transactional
    public ServiceRequestResponse restore(UUID id) {
        ServiceRequest entity = repository.findAnyById(id).orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())
            || !accessService.canAccessServiceRequest(entity)) {
            throw new NotFoundException("ServiceRequest not found: " + id);
        }
        requireDraftWithoutEffects(entity, "restored");
        entity.restore();
        return mapper.toResponse(repository.save(entity));
    }

    public ServiceRequest findEntity(UUID id) {
        ServiceRequest entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceRequest not found: " + id);
        }
        if (!accessService.canAccessServiceRequest(entity)) {
            throw new NotFoundException("ServiceRequest not found: " + id);
        }
        return entity;
    }

    private void beforeCreate(ServiceRequest entity) {
        beforeCreate(entity, false);
    }

    private void beforeCreate(ServiceRequest entity, boolean requireCompleteBasis) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(ServiceRequestStatus.DRAFT.name());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        validateLinkedReferences(entity);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("SR-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getNumber() == null || entity.getNumber().isBlank()) {
            entity.setNumber(entity.getCode());
        }
        if (entity.getTitle() == null || entity.getTitle().isBlank()) {
            entity.setTitle(entity.getName() == null || entity.getName().isBlank() || entity.getName().equalsIgnoreCase(entity.getCode())
                ? "Service Request"
                : entity.getName().trim());
        }
        if (entity.getName() == null || entity.getName().isBlank() || entity.getName().equalsIgnoreCase(entity.getCode())) {
            entity.setName(entity.getTitle());
        }
        entity.setUrgency(normalize(entity.getUrgency(), "NORMAL"));
        entity.setEstimatedAmount(nvl(entity.getEstimatedAmount()));
        entity.setApprovedAmount(nvl(entity.getApprovedAmount()));
        entity.setActualAmount(nvl(entity.getActualAmount()));
        validateNonNegative(entity.getEstimatedAmount(), "estimated amount");
        validateNonNegative(entity.getApprovedAmount(), "approved amount");
        validateNonNegative(entity.getActualAmount(), "actual amount");
        validatePlannedPeriod(entity);
        canonicalizeAcceptance(entity, requireCompleteBasis);
        entity.setBudgetReservedAmount(nvl(entity.getBudgetReservedAmount()));
    }

    private void validateLinkedReferences(ServiceRequest entity) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = entity.getCompanyId();

        if (entity.getDepartmentId() != null && departmentRepository != null) {
            departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                    entity.getDepartmentId(), tenantId, companyId
                )
                .orElseThrow(() -> new BusinessException("Department not found in current company"));
        }

        com.enterprise.mm.entity.User responsible = null;
        if (entity.getResponsibleUserId() != null && userRepository != null) {
            responsible = userRepository.findByIdAndIsDeletedFalse(entity.getResponsibleUserId())
                .filter(user -> tenantId.equals(user.getTenantId()))
                .filter(user -> companyId.equals(user.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Responsible user not found in current company"));
        }

        com.enterprise.mm.entity.Supplier supplier = null;
        if (entity.getSupplierId() != null && supplierRepository != null) {
            supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, entity.getSupplierId())
                .filter(item -> companyId.equals(item.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Supplier not found in current company"));
        }

        Contract contract = null;
        if (entity.getContractId() != null && contractRepository != null) {
            contract = contractRepository.findByIdAndIsDeletedFalse(entity.getContractId())
                .filter(item -> tenantId.equals(item.getTenantId()))
                .filter(item -> companyId.equals(item.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Contract not found in current company"));
        }

        com.enterprise.mm.entity.PurchaseOrder purchaseOrder = null;
        if (entity.getPurchaseOrderId() != null && purchaseOrderRepository != null) {
            purchaseOrder = purchaseOrderRepository
                .findByTenantIdAndIdAndIsDeletedFalse(tenantId, entity.getPurchaseOrderId())
                .filter(item -> companyId.equals(item.getCompanyId()))
                .filter(accessService::canAccessPurchaseOrder)
                .orElseThrow(() -> new BusinessException("Purchase order not found in permitted document scope"));
        }

        if (entity.getBudgetItemId() != null) {
            budgetItemService.get(entity.getBudgetItemId());
        }

        if (contract != null && supplier != null && !Objects.equals(contract.getSupplierId(), supplier.getId())) {
            throw new BusinessException("Contract supplier does not match service request supplier");
        }
        if (purchaseOrder != null && supplier != null && !Objects.equals(purchaseOrder.getSupplierId(), supplier.getId())) {
            throw new BusinessException("Purchase order supplier does not match service request supplier");
        }
        if (purchaseOrder != null && contract != null
            && !Objects.equals(purchaseOrder.getContractId(), contract.getId())) {
            throw new BusinessException("Purchase order contract does not match service request contract");
        }

        validateDepartmentActorScope(entity, responsible);
    }

    private void validateDepartmentActorScope(ServiceRequest entity, com.enterprise.mm.entity.User responsible) {
        boolean departmentHead = hasRole("DEPARTMENT_HEAD");
        boolean departmentWorker = hasRole("DEPARTMENT_WORKER");
        if (!departmentHead && !departmentWorker) {
            return;
        }
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!isPermittedDepartment(entity.getDepartmentId(), scope, departmentHead)) {
            throw new BusinessException("Department users can create or edit service requests only in their permitted department");
        }

        com.enterprise.mm.entity.User currentUser = currentCompanyUser();
        if (entity.getResponsibleUserId() == null) {
            if (currentUser == null) {
                throw new BusinessException("Current company user is required as service request owner");
            }
            entity.setResponsibleUserId(currentUser.getId());
            responsible = currentUser;
        }
        if (responsible == null) {
            throw new BusinessException("Responsible user is outside the permitted company scope");
        }
        boolean currentAssignee = currentUser != null && Objects.equals(currentUser.getId(), responsible.getId());
        boolean permittedTeamAssignee = departmentHead
            && isPermittedAssignee(responsible, entity.getDepartmentId(), scope);
        if (!currentAssignee && !permittedTeamAssignee) {
            throw new BusinessException("Responsible user is not a permitted assignee for this department");
        }
    }

    private boolean isPermittedDepartment(
        UUID departmentId,
        RoleDataScopeService.DataScope scope,
        boolean departmentHead
    ) {
        if (departmentId == null || scope == null) {
            return false;
        }
        return Objects.equals(departmentId, scope.departmentId());
    }

    private boolean isPermittedAssignee(
        com.enterprise.mm.entity.User user,
        UUID departmentId,
        RoleDataScopeService.DataScope scope
    ) {
        UUID assigneeDepartmentId = departmentIdOf(user);
        boolean samePermittedDepartment = Objects.equals(departmentId, assigneeDepartmentId)
            && (Objects.equals(assigneeDepartmentId, scope.departmentId())
                || scope.managedDepartmentIds().contains(assigneeDepartmentId));
        boolean teamPrincipal = scope.matchesTeamPrincipal(user.getUsername())
            || scope.matchesTeamPrincipal(user.getEmail())
            || scope.matchesTeamPrincipal(user.getFullName())
            || scope.matchesTeamPrincipal(user.getName());
        return samePermittedDepartment || (teamPrincipal && Objects.equals(departmentId, assigneeDepartmentId));
    }

    private UUID departmentIdOf(com.enterprise.mm.entity.User user) {
        if (user == null) {
            return null;
        }
        if (user.getDepartment() != null) {
            return user.getDepartment().getId();
        }
        return user.getEmployee() == null || user.getEmployee().getDepartment() == null
            ? null
            : user.getEmployee().getDepartment().getId();
    }

    private com.enterprise.mm.entity.User currentCompanyUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()
            || userRepository == null) {
            return null;
        }
        return userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), authentication.getName()
        ).orElse(null);
    }

    private boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority == null ? null : authority.getAuthority())
            .filter(Objects::nonNull)
            .map(value -> value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(role::equals);
    }

    private void beforeUpdate(ServiceRequest entity) {
        beforeCreate(entity);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void canonicalizeAcceptance(ServiceRequest entity, boolean requireCompleteBasis) {
        String mode = acceptanceMode(entity);
        entity.setAcceptanceMode(mode);
        if ("QUANTITY".equals(mode)) {
            entity.setOrderedQuantity(requiredPositive(entity.getOrderedQuantity(), "orderedQuantity"));
            if (entity.getUomCode() == null || entity.getUomCode().isBlank()) {
                throw new BusinessException("uomCode is required for QUANTITY acceptance");
            }
            if (entity.getOrderedAmount() != null || entity.getAcceptedAmount() != null || entity.getRejectedAmount() != null) {
                throw new BusinessException("QUANTITY acceptance cannot contain amount acceptance values");
            }
            entity.setUomCode(entity.getUomCode().trim().toUpperCase(Locale.ROOT));
            entity.setCurrencyCode(null);
            entity.setAcceptedQuantity(nonNegativeOrZero(entity.getAcceptedQuantity(), "acceptedQuantity"));
            entity.setRejectedQuantity(nonNegativeOrZero(entity.getRejectedQuantity(), "rejectedQuantity"));
            rejectOverAcceptance(entity.getAcceptedQuantity(), entity.getRejectedQuantity(), entity.getOrderedQuantity(), "quantity");
            entity.setAcceptedVolume(entity.getAcceptedQuantity());
            entity.setRejectedVolume(entity.getRejectedQuantity());
            return;
        }
        BigDecimal ordered = entity.getOrderedAmount();
        if (ordered == null) {
            ordered = nvl(entity.getApprovedAmount()).signum() > 0 ? entity.getApprovedAmount() : entity.getEstimatedAmount();
        }
        if (ordered == null || ordered.signum() <= 0) {
            if (requireCompleteBasis) {
                entity.setOrderedAmount(requiredPositive(ordered, "orderedAmount"));
            } else {
                entity.setOrderedAmount(null);
            }
        } else {
            entity.setOrderedAmount(ordered);
        }
        entity.setCurrencyCode(normalizeCurrency(entity.getCurrencyCode()));
        if (entity.getOrderedQuantity() != null || entity.getAcceptedQuantity() != null || entity.getRejectedQuantity() != null
            || (entity.getUomCode() != null && !entity.getUomCode().isBlank())) {
            throw new BusinessException("AMOUNT acceptance cannot contain quantity acceptance values");
        }
        entity.setOrderedQuantity(null);
        entity.setAcceptedQuantity(null);
        entity.setRejectedQuantity(null);
        entity.setUomCode(null);
        entity.setAcceptedAmount(nonNegativeOrZero(
            entity.getAcceptedAmount() == null ? entity.getAcceptedVolume() : entity.getAcceptedAmount(),
            "acceptedAmount"
        ));
        entity.setRejectedAmount(nonNegativeOrZero(
            entity.getRejectedAmount() == null ? entity.getRejectedVolume() : entity.getRejectedAmount(),
            "rejectedAmount"
        ));
        if (ordered == null && entity.getAcceptedAmount().add(entity.getRejectedAmount()).signum() > 0) {
            throw new BusinessException("orderedAmount must be positive before acceptance can start");
        }
        if (ordered != null && ordered.signum() > 0) {
            rejectOverAcceptance(entity.getAcceptedAmount(), entity.getRejectedAmount(), ordered, "amount");
        }
        entity.setAcceptedVolume(entity.getAcceptedAmount());
        entity.setRejectedVolume(entity.getRejectedAmount());
    }

    private String acceptanceMode(ServiceRequest entity) {
        String fallback = entity.getOrderedQuantity() == null ? "AMOUNT" : "QUANTITY";
        String mode = normalize(entity.getAcceptanceMode(), fallback);
        if (!List.of("AMOUNT", "QUANTITY").contains(mode)) {
            throw new BusinessException("acceptanceMode must be AMOUNT or QUANTITY");
        }
        return mode;
    }

    private BigDecimal orderedAmount(ServiceRequest entity) {
        BigDecimal ordered = entity.getOrderedAmount();
        if (ordered == null || ordered.signum() <= 0) {
            ordered = nvl(entity.getApprovedAmount()).signum() > 0 ? entity.getApprovedAmount() : entity.getEstimatedAmount();
        }
        return requiredPositive(ordered, "orderedAmount");
    }

    private BigDecimal requiredPositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(field + " must be positive");
        }
        return value;
    }

    private BigDecimal nonNegativeOrZero(BigDecimal value, String field) {
        BigDecimal effective = nvl(value);
        validateNonNegative(effective, field);
        return effective;
    }

    private void validateNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException(field + " cannot be negative");
        }
    }

    private void validatePlannedPeriod(ServiceRequest entity) {
        if (entity.getPlannedStartDate() != null && entity.getPlannedEndDate() != null
            && entity.getPlannedEndDate().isBefore(entity.getPlannedStartDate())) {
            throw new BusinessException("Planned period end cannot precede planned period start");
        }
    }

    private void rejectOverAcceptance(BigDecimal accepted, BigDecimal rejected, BigDecimal ordered, String basis) {
        if (nvl(accepted).add(nvl(rejected)).compareTo(ordered) > 0) {
            throw new BusinessException("Accepted and rejected " + basis + " exceeds ordered " + basis);
        }
    }

    private String normalizeCurrency(String currencyCode) {
        String normalized = currencyCode == null || currencyCode.isBlank() ? "UZS" : currencyCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3) {
            throw new BusinessException("currencyCode must contain three characters");
        }
        return normalized;
    }

    private void validateAcceptanceForProgress(ServiceRequest entity) {
        validateNonNegative(entity.getEstimatedAmount(), "estimated amount");
        validateNonNegative(entity.getApprovedAmount(), "approved amount");
        validatePlannedPeriod(entity);
        canonicalizeAcceptance(entity, true);
    }

    private boolean hasExplicitAcceptanceContract(ServiceRequestCreateRequest request) {
        return request != null && (
            (request.getAcceptanceMode() != null && !request.getAcceptanceMode().isBlank())
                || request.getOrderedAmount() != null
                || request.getOrderedQuantity() != null
                || (request.getCurrencyCode() != null && !request.getCurrencyCode().isBlank())
                || (request.getUomCode() != null && !request.getUomCode().isBlank())
        );
    }

    private boolean hasAcceptanceProgress(ServiceRequest entity) {
        return nvl(entity.getAcceptedVolume()).signum() > 0
            || nvl(entity.getRejectedVolume()).signum() > 0
            || nvl(entity.getAcceptedAmount()).signum() > 0
            || nvl(entity.getRejectedAmount()).signum() > 0
            || nvl(entity.getAcceptedQuantity()).signum() > 0
            || nvl(entity.getRejectedQuantity()).signum() > 0;
    }

    private void clearInactiveAcceptanceBasis(ServiceRequest entity, String mode) {
        entity.setAcceptedVolume(BigDecimal.ZERO);
        entity.setRejectedVolume(BigDecimal.ZERO);
        if ("AMOUNT".equals(mode)) {
            entity.setOrderedQuantity(null);
            entity.setAcceptedQuantity(null);
            entity.setRejectedQuantity(null);
            entity.setUomCode(null);
        } else {
            entity.setOrderedAmount(null);
            entity.setAcceptedAmount(null);
            entity.setRejectedAmount(null);
            entity.setCurrencyCode(null);
        }
    }

    private void requireDraftWithoutEffects(ServiceRequest request, String action) {
        if (!ServiceRequestStatus.DRAFT.name().equals(request.getStatus())) {
            throw new BusinessException("Only DRAFT service request can be " + action);
        }
        boolean hasEffects = !claimRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(
            TenantContext.currentTenant(), request.getId()).isEmpty();
        if (executionRepository != null) {
            hasEffects = hasEffects || !executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), request.getId()).isEmpty();
        }
        if (acceptanceActRepository != null) {
            hasEffects = hasEffects || !acceptanceActRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), request.getCompanyId(), request.getId()).isEmpty();
        }
        if (serviceOrderRepository != null) {
            hasEffects = hasEffects || !serviceOrderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), request.getCompanyId(), request.getId()).isEmpty();
        }
        if (hasEffects) {
            throw new BusinessException("Service request with dependent business effects cannot be " + action);
        }
    }

    private void requireCloseReadiness(ServiceRequest request) {
        if (!ServiceRequestStatus.PAID.name().equals(request.getStatus())) {
            throw new BusinessException("Service request cannot be closed before payment acknowledgement");
        }
        if (executionRepository != null) {
            List<com.enterprise.mm.entity.ServiceExecution> executions = executionRepository
                .findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TenantContext.currentTenant(), request.getId());
            if (executions.isEmpty() || executions.stream().anyMatch(item -> !List.of(
                com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED.name(),
                com.enterprise.mm.enums.ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
                com.enterprise.mm.enums.ServiceExecutionStatus.CANCELLED.name()
            ).contains(item.getStatus()))) {
                throw new BusinessException("Service request cannot close before all required executions are accepted or cancelled");
            }
        }
        if (acceptanceActRepository == null || attachmentService == null) {
            throw new BusinessException("Paid acceptance evidence and external payment reference are required before close");
        }
        List<com.enterprise.mm.entity.ServiceAcceptanceAct> acts = acceptanceActRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
                TenantContext.currentTenant(), request.getCompanyId(), request.getId());
        List<com.enterprise.mm.entity.ServiceAcceptanceAct> effectiveActs = acts.stream()
            .filter(act -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(act.getStatus()))
            .toList();
        if (effectiveActs.isEmpty()) {
            throw new BusinessException("Paid acceptance with external payment reference is required before close");
        }
        if (effectiveActs.stream().anyMatch(act ->
            !com.enterprise.mm.enums.ServiceAcceptanceActStatus.PAID.name().equals(act.getStatus())
                || act.getPaymentReference() == null
                || act.getPaymentReference().isBlank())) {
            throw new BusinessException("All active service acts require paid status and an external payment reference before close");
        }
        List<com.enterprise.mm.entity.ServiceAcceptanceAct> financialActs = effectiveActs.stream()
            .filter(act -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(act.getStatus()))
            .toList();
        if (!allServiceOrdersCovered(request, financialActs)) {
            throw new BusinessException("Every active service order requires a paid acceptance act before close");
        }
        if (!acceptedCoverageComplete(request, financialActs)) {
            throw new BusinessException("Full accepted service scope is required before close");
        }
        for (com.enterprise.mm.entity.ServiceAcceptanceAct act : financialActs) {
            if (attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId()).stream()
                .noneMatch(item -> "ACT".equals(item.getAttachmentType()))) {
                throw new BusinessException("Final ACT evidence is required for every paid service act before close");
            }
        }
        request.setActualAmount(financialActs.stream()
            .map(com.enterprise.mm.entity.ServiceAcceptanceAct::getAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private boolean allServiceOrdersCovered(
        ServiceRequest request,
        List<com.enterprise.mm.entity.ServiceAcceptanceAct> acts
    ) {
        if (serviceOrderRepository == null) {
            return false;
        }
        List<com.enterprise.mm.entity.ServiceOrder> orders = serviceOrderRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), request.getCompanyId(), request.getId());
        if (orders.isEmpty()) {
            return true;
        }
        java.util.Set<UUID> coveredOrderIds = acts.stream()
            .filter(act -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(act.getStatus()))
            .map(com.enterprise.mm.entity.ServiceAcceptanceAct::getServiceOrderId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        return orders.stream().allMatch(order -> coveredOrderIds.contains(order.getId()));
    }

    private boolean acceptedCoverageComplete(
        ServiceRequest request,
        List<com.enterprise.mm.entity.ServiceAcceptanceAct> acts
    ) {
        if ("QUANTITY".equals(acceptanceMode(request))) {
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
        BigDecimal covered = acts.stream()
            .filter(act -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(act.getStatus()))
            .map(com.enterprise.mm.entity.ServiceAcceptanceAct::getAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return covered.compareTo(ordered) >= 0;
    }

    private void adjustDraftBudgetReservation(ServiceRequest entity, UUID previousBudgetItemId, BigDecimal previousReservedAmount) {
        BigDecimal desired = desiredBudgetReservation(entity);
        boolean sameBudgetItem = Objects.equals(previousBudgetItemId, entity.getBudgetItemId());
        boolean reservationChanged = !sameBudgetItem || desired.compareTo(previousReservedAmount) != 0;
        if (!reservationChanged) {
            return;
        }
        boolean releasedPrevious = previousBudgetItemId != null && previousReservedAmount.signum() > 0;
        if (releasedPrevious) {
            budgetItemService.release(previousBudgetItemId, previousReservedAmount, "ServiceRequest " + entity.getCode());
        }
        boolean reserveDesired = entity.getBudgetItemId() != null && desired.signum() > 0;
        if (reserveDesired) {
            budgetItemService.reserve(entity.getBudgetItemId(), desired, "ServiceRequest " + entity.getCode());
        }
        if (reserveDesired) {
            entity.setBudgetReservedAmount(desired);
            entity.setBudgetReservedAt(LocalDateTime.now());
            entity.setBudgetReleasedAt(null);
        } else {
            entity.setBudgetReservedAmount(BigDecimal.ZERO);
            entity.setBudgetReservedAt(null);
            entity.setBudgetReleasedAt(releasedPrevious ? LocalDateTime.now() : null);
        }
    }

    private void releaseReservedBudget(ServiceRequest request, String source) {
        if (request.getBudgetItemId() == null || nvl(request.getBudgetReservedAmount()).signum() <= 0) {
            return;
        }
        budgetItemService.release(request.getBudgetItemId(), request.getBudgetReservedAmount(), source);
        request.setBudgetReservedAmount(BigDecimal.ZERO);
        request.setBudgetReservedAt(null);
        request.setBudgetReleasedAt(LocalDateTime.now());
    }

    private BigDecimal desiredBudgetReservation(ServiceRequest request) {
        BigDecimal approved = nvl(request.getApprovedAmount());
        return approved.signum() > 0 ? approved : nvl(request.getEstimatedAmount());
    }

    private String normalize(String value, String fallback) {
        String effective = value == null || value.isBlank() ? fallback : value;
        return effective.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private Specification<ServiceRequest> spec(ServiceRequestSearchRequest request) {
        return (root, query, cb) -> {
            ServiceRequestSearchRequest r = request == null
                ? new ServiceRequestSearchRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, false)
                : request;
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            if (!r.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }
            if (r.search() != null && !r.search().isBlank()) {
                String pattern = "%" + r.search().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("number")), pattern)
                ));
            }
            if (r.status() != null && !r.status().isEmpty()) {
                predicates.add(root.get("status").in(r.status()));
            }
            eq(predicates, cb, root.get("departmentId"), r.departmentId());
            eq(predicates, cb, root.get("responsibleUserId"), r.responsibleUserId());
            eq(predicates, cb, root.get("supplierId"), r.supplierId());
            eq(predicates, cb, root.get("contractId"), r.contractId());
            eq(predicates, cb, root.get("purchaseOrderId"), r.purchaseOrderId());
            eq(predicates, cb, root.get("budgetItemId"), r.budgetItemId());
            if (r.category() != null) predicates.add(cb.equal(root.get("category"), r.category()));
            if (r.urgency() != null) predicates.add(cb.equal(root.get("urgency"), normalize(r.urgency(), r.urgency())));
            dateTimeRange(predicates, cb, root.get("createdAt"), r.dateFrom(), r.dateTo());
            dateRange(predicates, cb, root.get("plannedStartDate"), r.plannedStartFrom(), r.plannedStartTo());
            dateRange(predicates, cb, root.get("plannedEndDate"), r.plannedEndFrom(), r.plannedEndTo());
            if (r.estimatedAmountMin() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("estimatedAmount"), r.estimatedAmountMin()));
            if (r.estimatedAmountMax() != null) predicates.add(cb.lessThanOrEqualTo(root.get("estimatedAmount"), r.estimatedAmountMax()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private <T> void eq(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Path<T> path, T value) {
        if (value != null) {
            predicates.add(cb.equal(path, value));
        }
    }

    private void dateRange(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Path<LocalDate> path, LocalDate from, LocalDate to) {
        if (from != null) predicates.add(cb.greaterThanOrEqualTo(path, from));
        if (to != null) predicates.add(cb.lessThanOrEqualTo(path, to));
    }

    private void dateTimeRange(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Path<LocalDateTime> path, LocalDate from, LocalDate to) {
        if (from != null) predicates.add(cb.greaterThanOrEqualTo(path, from.atStartOfDay()));
        if (to != null) predicates.add(cb.lessThanOrEqualTo(path, LocalDateTime.of(to, LocalTime.MAX)));
    }
}
