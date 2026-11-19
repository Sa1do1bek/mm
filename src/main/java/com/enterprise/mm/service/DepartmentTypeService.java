package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.DepartmentTypeResponse;
import com.enterprise.mm.payload.DepartmentTypeUpdateRequest;
import com.enterprise.mm.payload.DepartmentTypeCreateRequest;
import com.enterprise.mm.payload.DepartmentTypeSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DepartmentTypeMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DepartmentType;
import com.enterprise.mm.repository.DepartmentTypeRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DepartmentTypeService {
    private final DepartmentTypeRepository repository;
    private final DepartmentTypeMapper mapper;
    private final String entityName = "DepartmentType";
    private final AuditService auditService;
    protected void beforeCreate(DepartmentType entity, DepartmentTypeCreateRequest dto) {
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

        normalize(entity);
    }
    protected void beforeUpdate(DepartmentType entity, DepartmentTypeUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    private void normalize(DepartmentType entity) {
        if (entity.getCode() != null) {
            entity.setCode(entity.getCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getLevelCode() != null) {
            entity.setLevelCode(entity.getLevelCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getHierarchyLevel() == null) {
            entity.setHierarchyLevel(99);
        }
    }
    @Transactional(readOnly = true)
    public Page<DepartmentTypeResponse> search(DepartmentTypeSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public DepartmentTypeResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DepartmentTypeResponse create(DepartmentTypeCreateRequest dto) {
        DepartmentType entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        DepartmentType saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public DepartmentTypeResponse update(UUID id, DepartmentTypeUpdateRequest dto) {
        DepartmentType entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        DepartmentType saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        DepartmentType entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DepartmentTypeResponse restore(UUID id) {
        DepartmentType entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        DepartmentType saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected DepartmentType findEntity(UUID id) {
        DepartmentType entity = repository.findByIdAndIsDeletedFalse(id)
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
}

