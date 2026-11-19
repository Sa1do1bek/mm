package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApprovalWorkflowResponse;
import com.enterprise.mm.payload.ApprovalWorkflowUpdateRequest;
import com.enterprise.mm.payload.ApprovalWorkflowCreateRequest;
import com.enterprise.mm.payload.ApprovalWorkflowSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ApprovalWorkflowMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalWorkflow;
import com.enterprise.mm.repository.ApprovalWorkflowRepository;
import com.enterprise.mm.service.approval.ApprovalModuleRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalWorkflowService {
    private final ApprovalWorkflowRepository repository;
    private final ApprovalWorkflowMapper mapper;
    private final String entityName = "ApprovalWorkflow";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<ApprovalWorkflowResponse> search(ApprovalWorkflowSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        String searchPattern = "%" + filter.normalizedSearch().toLowerCase(Locale.ROOT) + "%";
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            !filter.normalizedSearch().isBlank(),
            searchPattern,
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            normalizedModuleKey(request.moduleKey()),
            normalizedRequestType(request.requestType()),
            filter.referenceId(),
            filter.normalizedReferenceEntityType(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ApprovalWorkflowResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ApprovalWorkflowResponse create(ApprovalWorkflowCreateRequest dto) {
        ApprovalWorkflow entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ApprovalWorkflow saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ApprovalWorkflowResponse update(UUID id, ApprovalWorkflowUpdateRequest dto) {
        ApprovalWorkflow entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ApprovalWorkflow saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ApprovalWorkflow entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ApprovalWorkflowResponse restore(UUID id) {
        ApprovalWorkflow entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ApprovalWorkflow saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ApprovalWorkflow findEntity(UUID id) {
        ApprovalWorkflow entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(ApprovalWorkflow entity, ApprovalWorkflowCreateRequest dto) {
        normalizeApprovalRouting(entity);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(ApprovalWorkflow entity, ApprovalWorkflowUpdateRequest dto) {
        normalizeApprovalRouting(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void normalizeApprovalRouting(ApprovalWorkflow entity) {
        String moduleKey = normalizedModuleKey(entity.getModuleKey());
        if (moduleKey == null) {
            moduleKey = normalizedModuleKey(entity.getEntityType());
        }
        entity.setModuleKey(moduleKey);
        entity.setRequestType(normalizedRequestType(entity.getRequestType()));
    }

    private String normalizedModuleKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = ApprovalModuleRegistry.normalize(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizedRequestType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RequestType.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported request type: " + value);
        }
    }
}
