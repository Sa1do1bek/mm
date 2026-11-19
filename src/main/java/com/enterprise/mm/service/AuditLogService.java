package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AuditLogMapper;
import com.enterprise.mm.payload.AuditLogCreateRequest;
import com.enterprise.mm.payload.AuditLogResponse;
import com.enterprise.mm.payload.AuditLogUpdateRequest;
import com.enterprise.mm.payload.AuditLogSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.AuditLogRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;
    private final AuditLogMapper mapper;
    private final String entityName = "AuditLog";

    public AuditLogResponse create(AuditLogCreateRequest dto) {
        throw immutable();
    }

    public AuditLogResponse update(UUID id, AuditLogUpdateRequest dto) {
        throw immutable();
    }

    public void softDelete(UUID id) {
        throw immutable();
    }

    public AuditLogResponse restore(UUID id) {
        throw immutable();
    }

    private BusinessException immutable() {
        return new BusinessException("Audit log is immutable");
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(AuditLogSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        java.util.List<String> actions = normalizedActions(request);
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            actions,
            actions.isEmpty(),
            filter.fromDateTime(),
            filter.toDateTime(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AuditLogResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    protected AuditLog findEntity(UUID id) {
        AuditLog entity = repository.findByIdAndIsDeletedFalse(id)
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

    protected void beforeCreate(AuditLog entity, AuditLogCreateRequest dto) {
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

    protected void beforeUpdate(AuditLog entity, AuditLogUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    protected boolean companyScoped() {
        return true;
    }

    private java.util.List<String> normalizedActions(AuditLogSearchRequest request) {
        if (request == null || request.action() == null) {
            return java.util.List.of();
        }
        return request.action().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> value.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
    }
}
