package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Role;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RoleMapper;
import com.enterprise.mm.payload.RoleCreateRequest;
import com.enterprise.mm.payload.RoleResponse;
import com.enterprise.mm.payload.RoleUpdateRequest;
import com.enterprise.mm.payload.RoleSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.RoleRepository;
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
public class RoleService {
    private final RoleRepository repository;
    private final RoleMapper mapper;
    private final String entityName = "Role";
    private final AuditService auditService;

    protected void beforeCreate(Role entity, RoleCreateRequest dto) {
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
        if (entity.getRoleName() == null || entity.getRoleName().isBlank()) {
            entity.setRoleName(entity.getCode());
        }
        applyRoleScope(entity, dto);
        validateUniqueRoleName(entity);
    }

    @Transactional(readOnly = true)
    public Page<RoleResponse> search(RoleSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            filter.fromDateTime(),
            filter.toDateTime(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public RoleResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public RoleResponse create(RoleCreateRequest dto) {
        Role entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Role saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public RoleResponse update(UUID id, RoleUpdateRequest dto) {
        Role entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Role saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        Role entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public RoleResponse restore(UUID id) {
        Role entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && entity.getCompanyId() != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Role saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected Role findEntity(UUID id) {
        Role entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && entity.getCompanyId() != null && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected void beforeUpdate(Role entity, RoleUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        applyRoleScope(entity, dto);
        validateUniqueRoleName(entity);
    }

    protected boolean companyScoped() {
        return true;
    }

    private void applyRoleScope(Role entity, RoleCreateRequest dto) {
        if (Boolean.TRUE.equals(dto.getGlobalRole())) {
            entity.setCompanyId(null);
            entity.setGlobalScope(true);
        } else if (Boolean.FALSE.equals(dto.getGlobalRole()) && entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
            entity.setGlobalScope(false);
        }
    }

    private void validateUniqueRoleName(Role entity) {
        if (entity.getRoleName() == null || entity.getRoleName().isBlank()) {
            return;
        }
        var existing = entity.getCompanyId() == null
            ? repository.findByTenantIdAndCompanyIdIsNullAndRoleNameAndIsDeletedFalse(TenantContext.currentTenant(), entity.getRoleName())
            : repository.findByTenantIdAndCompanyIdAndRoleNameAndIsDeletedFalse(TenantContext.currentTenant(), entity.getCompanyId(), entity.getRoleName());
        existing
            .filter(role -> entity.getId() == null || !entity.getId().equals(role.getId()))
            .ifPresent(role -> {
                throw new BadRequestException("Role name already exists in this scope: " + entity.getRoleName());
            });
    }
}

