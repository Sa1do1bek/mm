package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceExecutionMapper;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceExecutionSearchRequest;
import com.enterprise.mm.payload.ServiceExecutionUpdateRequest;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {"search", "get", "overdue"})
public class ServiceExecutionService {
    private static final Set<String> MUTABLE_UPDATE_STATUSES = Set.of(
        ServiceExecutionStatus.NOT_STARTED.name(),
        ServiceExecutionStatus.IN_PROGRESS.name(),
        ServiceExecutionStatus.PAUSED.name()
    );
    private final ServiceExecutionRepository repository;
    private final ServiceRequestRepository requestRepository;
    private final ServiceExecutionMapper mapper;
    private final AuditService auditService;
    private final ServiceWorkflowNotificationService notificationService;
    private final ServiceReviewHistoryService historyService;
    private final RoleDataScopeService roleDataScopeService;
    private final ServiceWorkflowAccessService accessService;
    private final ServiceOrderRepository serviceOrderRepository;


    @Autowired
    public ServiceExecutionService(
        ServiceExecutionRepository repository,
        ServiceRequestRepository requestRepository,
        ServiceExecutionMapper mapper,
        AuditService auditService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService,
        ServiceOrderRepository serviceOrderRepository
    ) {
        this.repository = repository;
        this.requestRepository = requestRepository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.historyService = historyService;
        this.roleDataScopeService = roleDataScopeService;
        this.accessService = accessService;
        this.serviceOrderRepository = serviceOrderRepository;
    }

    public ServiceExecutionService(
        ServiceExecutionRepository repository,
        ServiceRequestRepository requestRepository,
        ServiceExecutionMapper mapper,
        AuditService auditService,
        ServiceWorkflowNotificationService notificationService,
        ServiceReviewHistoryService historyService,
        RoleDataScopeService roleDataScopeService,
        ServiceWorkflowAccessService accessService
    ) {
        this(repository, requestRepository, mapper, auditService, notificationService, historyService,
            roleDataScopeService, accessService, null);
    }

    @Transactional(readOnly = true)
    public Page<ServiceExecutionResponse> search(ServiceExecutionSearchRequest request, Pageable pageable) {
        if (accessService.hasFullAccess()) {
            return repository.findAll(spec(request), pageable).map(mapper::toResponse);
        }
        Page<ServiceExecution> records = repository.findAll(spec(request), Pageable.unpaged());
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> accessService.canAccessServiceExecution(entity, request(entity.getServiceRequestId())),
            mapper::toResponse
        );
    }

    @Transactional(readOnly = true)
    public ServiceExecutionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<ServiceExecutionResponse> overdue(ServiceExecutionSearchRequest request, Pageable pageable) {
        ServiceExecutionSearchRequest effective = request == null
            ? new ServiceExecutionSearchRequest(null, null, null, null, null, null, null, null, null, true, false)
            : new ServiceExecutionSearchRequest(
                request.search(),
                request.status(),
                request.serviceRequestId(),
                request.serviceOrderId(),
                request.responsibleUserId(),
                request.plannedStartFrom(),
                request.plannedStartTo(),
                request.plannedEndFrom(),
                request.plannedEndTo(),
                true,
                request.includeDeleted()
            );
        return search(effective, pageable);
    }

    @Transactional
    public ServiceExecutionResponse create(ServiceExecutionCreateRequest request) {
        ServiceExecution entity = mapper.toEntity(request);
        beforeCreate(entity);
        ServiceExecution saved = repository.save(entity);
        ServiceRequest sr = request(entity.getServiceRequestId());
        if (ServiceRequestStatus.APPROVED.name().equals(sr.getStatus()) || ServiceRequestStatus.WAITING_EXECUTION.name().equals(sr.getStatus())) {
            sr.setStatus(ServiceRequestStatus.IN_PROGRESS.name());
            requestRepository.save(sr);
        }
        auditService.log("ServiceExecution", saved.getId(), "CREATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceExecutionResponse update(UUID id, ServiceExecutionUpdateRequest request) {
        ServiceExecution entity = findEntity(id);
        if (!MUTABLE_UPDATE_STATUSES.contains(entity.getStatus())) {
            throw new BusinessException("Service execution cannot be edited in status " + entity.getStatus());
        }
        if (!Objects.equals(entity.getServiceRequestId(), request.getServiceRequestId())) {
            throw new BusinessException("serviceRequestId cannot be changed after service execution creation");
        }
        if (!Objects.equals(entity.getServiceOrderId(), request.getServiceOrderId())) {
            throw new BusinessException("serviceOrderId cannot be changed after service execution creation");
        }
        String currentStatus = entity.getStatus();
        mapper.updateEntity(entity, request);
        entity.setStatus(currentStatus);
        beforeUpdate(entity);
        ServiceExecution saved = repository.save(entity);
        auditService.log("ServiceExecution", id, "UPDATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceExecutionResponse start(UUID id, ServiceActionRequest action) {
        ServiceExecution execution = findEntity(id);
        requireStatus(execution, ServiceExecutionStatus.NOT_STARTED, ServiceExecutionStatus.PAUSED, ServiceExecutionStatus.REJECTED);
        execution.setStatus(ServiceExecutionStatus.IN_PROGRESS.name());
        if (execution.getActualStartDate() == null) {
            execution.setActualStartDate(LocalDate.now());
        }
        updateRequestStatus(execution.getServiceRequestId(), ServiceRequestStatus.IN_PROGRESS);
        historyService.record(execution.getServiceRequestId(), null, "EXECUTION", "STARTED", action == null ? null : action.comment());
        return mapper.toResponse(repository.save(execution));
    }

    @Transactional
    public ServiceExecutionResponse submit(UUID id, ServiceActionRequest action) {
        ServiceExecution execution = findEntity(id);
        requireStatus(execution, ServiceExecutionStatus.IN_PROGRESS, ServiceExecutionStatus.PAUSED);
        execution.setStatus(ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR.name());
        execution.setProgressPercent(BigDecimal.valueOf(100));
        execution.setActualEndDate(execution.getActualEndDate() == null ? LocalDate.now() : execution.getActualEndDate());
        updateRequestStatus(execution.getServiceRequestId(), ServiceRequestStatus.WAITING_ACCEPTANCE);
        notificationService.reviewRequired("SERVICE_MANAGER", execution.getCode());
        historyService.record(execution.getServiceRequestId(), null, "EXECUTION", "SUBMITTED", action == null ? null : action.comment());
        return mapper.toResponse(repository.save(execution));
    }

    @Transactional
    public ServiceExecutionResponse accept(UUID id, ServiceActionRequest action) {
        ServiceExecution execution = findEntity(id);
        requireStatus(execution, ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR, ServiceExecutionStatus.UNDER_REVIEW);
        execution.setStatus(action != null && action.comment() != null && !action.comment().isBlank()
            ? ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name()
            : ServiceExecutionStatus.ACCEPTED.name());
        ServiceExecution saved = repository.save(execution);
        updateRequestAfterAcceptance(execution.getServiceRequestId());
        historyService.record(execution.getServiceRequestId(), null, "TECHNICAL", "APPROVED", action == null ? null : action.comment());
        return mapper.toResponse(saved);
    }

    @Transactional
    public ServiceExecutionResponse reject(UUID id, ServiceActionRequest action) {
        ServiceExecution execution = findEntity(id);
        requireStatus(execution, ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR, ServiceExecutionStatus.UNDER_REVIEW);
        execution.setStatus(ServiceExecutionStatus.REJECTED.name());
        historyService.record(execution.getServiceRequestId(), null, "TECHNICAL", "REJECTED", action == null ? null : action.comment());
        return mapper.toResponse(repository.save(execution));
    }

    @Transactional
    public void softDelete(UUID id) {
        ServiceExecution entity = findEntity(id);
        requireNotStartedForDeletion(entity, "deleted");
        entity.softDelete();
        repository.save(entity);
        auditService.log("ServiceExecution", id, "SOFT_DELETE", entity.getCode(), null, null);
    }

    @Transactional
    public ServiceExecutionResponse restore(UUID id) {
        ServiceExecution entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException("ServiceExecution not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceExecution not found: " + id);
        }
        ServiceRequest request = request(entity.getServiceRequestId());
        if (!accessService.canAccessServiceExecution(entity, request)) {
            throw new NotFoundException("ServiceExecution not found: " + id);
        }
        requireNotStartedForDeletion(entity, "restored");
        entity.restore();
        return mapper.toResponse(repository.save(entity));
    }

    protected ServiceExecution findEntity(UUID id) {
        ServiceExecution entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("ServiceExecution not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException("ServiceExecution not found: " + id);
        }
        if (!accessService.canAccessServiceExecution(entity, request(entity.getServiceRequestId()))) {
            throw new NotFoundException("ServiceExecution not found: " + id);
        }
        return entity;
    }

    private void beforeCreate(ServiceExecution entity) {
        ServiceRequest request = request(entity.getServiceRequestId());
        if (!List.of(ServiceRequestStatus.APPROVED.name(), ServiceRequestStatus.WAITING_EXECUTION.name(), ServiceRequestStatus.IN_PROGRESS.name()).contains(request.getStatus())) {
            throw new BusinessException("Execution cannot start before service request approval");
        }
        validateServiceOrderLink(entity, request);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("SE-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), request.getName()
        ));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(ServiceExecutionStatus.NOT_STARTED.name());
        }
        entity.setProgressPercent(entity.getProgressPercent() == null ? BigDecimal.ZERO : entity.getProgressPercent());
    }

    private void beforeUpdate(ServiceExecution entity) {
        ServiceRequest request = request(entity.getServiceRequestId());
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), request.getName()
        ));
        entity.setProgressPercent(entity.getProgressPercent() == null ? BigDecimal.ZERO : entity.getProgressPercent());
    }

    private void validateServiceOrderLink(ServiceExecution execution, ServiceRequest request) {
        if (execution.getServiceOrderId() == null) {
            return;
        }
        if (serviceOrderRepository == null) {
            throw new BusinessException("Service order validation is unavailable");
        }
        com.enterprise.mm.entity.ServiceOrder order = serviceOrderRepository
            .findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), execution.getServiceOrderId())
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Service order not found"));
        if (!Objects.equals(order.getServiceRequestId(), request.getId())) {
            throw new BusinessException("Service order does not belong to service request");
        }
        if (!accessService.canAccessServiceOrder(order, request)) {
            throw new BusinessException("Service order not found");
        }
    }

    private ServiceRequest request(UUID id) {
        if (id == null) {
            throw new BusinessException("serviceRequestId is required");
        }
        return requestRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(accessService::canAccessServiceRequest)
            .orElseThrow(() -> new BusinessException("Service request not found"));
    }

    private void updateRequestStatus(UUID serviceRequestId, ServiceRequestStatus status) {
        ServiceRequest request = request(serviceRequestId);
        request.setStatus(status.name());
        requestRepository.save(request);
    }

    private void updateRequestAfterAcceptance(UUID serviceRequestId) {
        ServiceRequest request = request(serviceRequestId);
        List<ServiceExecution> executions = repository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(
            TenantContext.currentTenant(), serviceRequestId);
        boolean allRequiredAccepted = !executions.isEmpty() && executions.stream().allMatch(item -> List.of(
            ServiceExecutionStatus.ACCEPTED.name(),
            ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
            ServiceExecutionStatus.CANCELLED.name()
        ).contains(item.getStatus()));
        request.setStatus(allRequiredAccepted
            ? ServiceRequestStatus.ACCEPTED.name()
            : ServiceRequestStatus.WAITING_ACCEPTANCE.name());
        requestRepository.save(request);
    }

    private void requireNotStartedForDeletion(ServiceExecution execution, String action) {
        if (!ServiceExecutionStatus.NOT_STARTED.name().equals(execution.getStatus())) {
            throw new BusinessException("Only NOT_STARTED service execution can be " + action);
        }
    }

    private void requireStatus(ServiceExecution execution, ServiceExecutionStatus... statuses) {
        for (ServiceExecutionStatus status : statuses) {
            if (status.name().equals(execution.getStatus())) {
                return;
            }
        }
        throw new BusinessException("Invalid execution status: " + execution.getStatus());
    }

    private Specification<ServiceExecution> spec(ServiceExecutionSearchRequest request) {
        return (root, query, cb) -> {
            ServiceExecutionSearchRequest r = request == null
                ? new ServiceExecutionSearchRequest(null, null, null, null, null, null, null, null, null, false, false)
                : request;
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            if (!r.includeDeleted()) predicates.add(cb.isFalse(root.get("isDeleted")));
            if (r.search() != null && !r.search().isBlank()) {
                String pattern = "%" + r.search().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("code")), pattern), cb.like(cb.lower(root.get("name")), pattern)));
            }
            if (r.status() != null && !r.status().isEmpty()) predicates.add(root.get("status").in(r.status()));
            if (r.serviceRequestId() != null) predicates.add(cb.equal(root.get("serviceRequestId"), r.serviceRequestId()));
            if (r.serviceOrderId() != null) predicates.add(cb.equal(root.get("serviceOrderId"), r.serviceOrderId()));
            if (r.responsibleUserId() != null) predicates.add(cb.equal(root.get("responsibleUserId"), r.responsibleUserId()));
            if (r.plannedStartFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("plannedStartDate"), r.plannedStartFrom()));
            if (r.plannedStartTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("plannedStartDate"), r.plannedStartTo()));
            if (r.plannedEndFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("plannedEndDate"), r.plannedEndFrom()));
            if (r.plannedEndTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("plannedEndDate"), r.plannedEndTo()));
            if (r.overdueOnly()) {
                predicates.add(cb.isNotNull(root.get("plannedEndDate")));
                predicates.add(cb.lessThan(root.get("plannedEndDate"), LocalDate.now()));
                predicates.add(root.get("status").in(List.of(
                    ServiceExecutionStatus.NOT_STARTED.name(),
                    ServiceExecutionStatus.IN_PROGRESS.name(),
                    ServiceExecutionStatus.PAUSED.name(),
                    ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR.name(),
                    ServiceExecutionStatus.UNDER_REVIEW.name()
                )));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
