package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.SupplierCorrectiveActionUpdateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierCorrectiveActionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierCorrectiveActionSeverity;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierCorrectiveActionService {
    private final String entityName = "SupplierCorrectiveAction";
    private final SupplierCorrectiveActionRepository repository;
    private final SupplierCorrectiveActionMapper mapper;
    private final SupplierComplianceService complianceService;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    @Transactional
    public SupplierCorrectiveActionResponse create(SupplierCorrectiveActionCreateRequest dto) {
        SupplierCorrectiveAction entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierCorrectiveAction savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        SupplierCorrectiveActionResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    @Transactional
    public SupplierCorrectiveActionResponse update(UUID id, SupplierCorrectiveActionUpdateRequest dto) {
        SupplierCorrectiveAction entity = findMutationEntity(id);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierCorrectiveAction savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        SupplierCorrectiveActionResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    @Transactional
    public SupplierCorrectiveActionResponse open(UUID id) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (!SupplierCorrectiveActionStatus.DRAFT.name().equals(action.getStatus())) {
            throw new BusinessException("Only DRAFT CAPA can be opened");
        }
        action.setStatus(SupplierCorrectiveActionStatus.OPEN.name());
        auditService.log("SupplierCorrectiveAction", id, "OPEN", action.getIssueTitle(), null, null);
        return saveAndApply(action);
    }
    @Transactional
    public SupplierCorrectiveActionResponse start(UUID id) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (!SupplierCorrectiveActionStatus.OPEN.name().equals(action.getStatus()) && !SupplierCorrectiveActionStatus.WAITING_SUPPLIER.name().equals(action.getStatus())) {
            throw new BusinessException("Only OPEN or WAITING_SUPPLIER CAPA can be started");
        }
        action.setStatus(SupplierCorrectiveActionStatus.IN_PROGRESS.name());
        auditService.log("SupplierCorrectiveAction", id, "START", action.getIssueTitle(), null, null);
        return saveAndApply(action);
    }
    @Transactional
    public SupplierCorrectiveActionResponse submit(UUID id, SupplierCapaActionRequest request) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (!SupplierCorrectiveActionStatus.IN_PROGRESS.name().equals(action.getStatus()) && !SupplierCorrectiveActionStatus.WAITING_SUPPLIER.name().equals(action.getStatus())) {
            throw new BusinessException("Only IN_PROGRESS or WAITING_SUPPLIER CAPA can be submitted");
        }
        if (isBlank(action.getRootCause()) || isBlank(action.getCorrectiveAction()) || isBlank(action.getPreventiveAction())) {
            throw new BusinessException("CAPA requires rootCause, correctiveAction and preventiveAction before submit");
        }
        action.setStatus(SupplierCorrectiveActionStatus.SUBMITTED.name());
        action.setSubmittedAt(LocalDateTime.now());
        auditService.log("SupplierCorrectiveAction", id, "SUBMIT", request == null ? null : request.comment(), null, null);
        return saveAndApply(action);
    }
    @Transactional
    public SupplierCorrectiveActionResponse verify(UUID id, SupplierCapaActionRequest request) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (!SupplierCorrectiveActionStatus.SUBMITTED.name().equals(action.getStatus())) {
            throw new BusinessException("Only SUBMITTED CAPA can be verified");
        }
        action.setStatus(SupplierCorrectiveActionStatus.VERIFIED.name());
        action.setVerifiedAt(LocalDateTime.now());
        action.setVerificationResult(request == null ? "Verified" : request.comment());
        action.setRecurrencePrevented(true);
        auditService.log("SupplierCorrectiveAction", id, "VERIFY", action.getVerificationResult(), null, null);
        return saveAndApply(action);
    }
    @Transactional
    public SupplierCorrectiveActionResponse close(UUID id) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (!SupplierCorrectiveActionStatus.VERIFIED.name().equals(action.getStatus())) {
            throw new BusinessException("Only VERIFIED CAPA can be closed");
        }
        action.setStatus(SupplierCorrectiveActionStatus.CLOSED.name());
        action.setClosedAt(LocalDateTime.now());
        auditService.log("SupplierCorrectiveAction", id, "CLOSE", action.getIssueTitle(), null, null);
        return saveAndApply(action);
    }
    @Transactional
    public SupplierCorrectiveActionResponse cancel(UUID id, SupplierCapaActionRequest request) {
        SupplierCorrectiveAction action = findMutationEntity(id);
        if (SupplierCorrectiveActionStatus.CLOSED.name().equals(action.getStatus())) {
            throw new BusinessException("Closed CAPA cannot be cancelled");
        }
        action.setStatus(SupplierCorrectiveActionStatus.CANCELLED.name());
        auditService.log("SupplierCorrectiveAction", id, "CANCEL", request == null ? null : request.comment(), null, null);
        return saveAndApply(action);
    }
    protected void beforeCreate(SupplierCorrectiveAction entity, SupplierCorrectiveActionCreateRequest dto) {
        var supplier = complianceService.validateSupplier(entity.getSupplierId());
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        entity.setStatus(SupplierCorrectiveActionStatus.DRAFT.name());
        entity.setSubmittedAt(null);
        entity.setVerifiedAt(null);
        entity.setClosedAt(null);
        entity.setVerificationResult(null);
        entity.setRecurrencePrevented(false);
        normalize(entity);
        entity.setCompanyId(CompanyContext.currentCompany());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(SupplierCorrectiveAction entity, SupplierCorrectiveActionUpdateRequest dto) {
        complianceService.validateSupplier(entity.getSupplierId());
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private SupplierCorrectiveActionResponse saveAndApply(SupplierCorrectiveAction action) {
        SupplierCorrectiveAction saved = repository.save(action);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return mapper.toResponse(saved);
    }
    private void normalize(SupplierCorrectiveAction entity) {
        entity.setSeverity(complianceService.normalizeCode(entity.getSeverity(), SupplierCorrectiveActionSeverity.MEDIUM.name(), "severity", enumNames(SupplierCorrectiveActionSeverity.values())));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(SupplierCorrectiveActionStatus.DRAFT.name());
        } else {
            entity.setStatus(complianceService.normalizeCode(entity.getStatus(), SupplierCorrectiveActionStatus.DRAFT.name(), "status", enumNames(SupplierCorrectiveActionStatus.values())));
        }
        if (entity.getDueDate() != null && entity.getDueDate().isBefore(LocalDate.now()) && isOpenLifecycle(entity.getStatus())) {
            entity.setStatus(SupplierCorrectiveActionStatus.OVERDUE.name());
        }
        if (entity.getOwnerEmployeeId() != null && employeeRepository.findByIdAndIsDeletedFalse(entity.getOwnerEmployeeId()).isEmpty()) {
            throw new BusinessException("ownerEmployeeId not found");
        }
    }
    private boolean isOpenLifecycle(String status) {
        return SupplierCorrectiveActionStatus.OPEN.name().equals(status)
            || SupplierCorrectiveActionStatus.IN_PROGRESS.name().equals(status)
            || SupplierCorrectiveActionStatus.WAITING_SUPPLIER.name().equals(status)
            || SupplierCorrectiveActionStatus.SUBMITTED.name().equals(status);
    }
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    @Transactional(readOnly = true)
    public Page<SupplierCorrectiveActionResponse> search(SupplierCorrectiveActionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            SearchCriteria.normalizedToken(request.sourceType()),
            request.sourceId(),
            likePattern(request.issueTitle()),
            request.ownerEmployeeId(),
            likePattern(request.supplierResponsibleName()),
            SearchCriteria.normalizedToken(request.severity()),
            request.dueDateFrom(),
            request.dueDateTo(),
            request.submittedFrom() == null ? null : request.submittedFrom().atStartOfDay(),
            request.submittedTo() == null ? null : request.submittedTo().atTime(java.time.LocalTime.MAX),
            request.verifiedFrom() == null ? null : request.verifiedFrom().atStartOfDay(),
            request.verifiedTo() == null ? null : request.verifiedTo().atTime(java.time.LocalTime.MAX),
            request.closedFrom() == null ? null : request.closedFrom().atStartOfDay(),
            request.closedTo() == null ? null : request.closedTo().atTime(java.time.LocalTime.MAX),
            likePattern(request.verificationResult()),
            request.recurrencePrevented(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public SupplierCorrectiveActionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierCorrectiveAction entity = findMutationEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
        complianceService.applySupplierRisk(entity.getSupplierId());
    }
    @Transactional
    public SupplierCorrectiveActionResponse restore(UUID id) {
        SupplierCorrectiveAction entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireCurrentCompany(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierCorrectiveAction saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return mapper.toResponse(saved);
    }
    protected SupplierCorrectiveAction findEntity(UUID id) {
        SupplierCorrectiveAction entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private SupplierCorrectiveAction findMutationEntity(UUID id) {
        SupplierCorrectiveAction entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void requireCurrentCompany(SupplierCorrectiveAction entity) {
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private String likePattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
