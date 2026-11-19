package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.IncotermResponse;
import com.enterprise.mm.payload.IncotermUpdateRequest;
import com.enterprise.mm.payload.IncotermCreateRequest;
import com.enterprise.mm.payload.IncotermSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.IncotermMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Incoterm;
import com.enterprise.mm.repository.IncotermRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncotermService {
    private final IncotermMapper mapper;
    private final String entityName = "Incoterm";
    private final AuditService auditService;
    private static final String[] OFFICIAL_CODES = {"EXW", "FCA", "FAS", "FOB", "CFR", "CIF", "CPT", "CIP", "DAP", "DPU", "DDP"};
    private final IncotermRepository repository;
    protected void beforeCreate(Incoterm entity, IncotermCreateRequest dto) {
        normalize(entity);
        ensureUnique(entity);
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
    protected void beforeUpdate(Incoterm entity, IncotermUpdateRequest dto) {
        normalize(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void normalize(Incoterm entity) {
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            throw new BusinessException("Incoterm code is required");
        }
        entity.setCode(entity.getCode().trim().toUpperCase(Locale.ROOT));
        boolean official = false;
        for (String code : OFFICIAL_CODES) {
            official = official || code.equals(entity.getCode());
        }
        if (!official) {
            throw new BusinessException("Unsupported Incoterm code: " + entity.getCode());
        }
        if (entity.getVersionYear() == null) {
            entity.setVersionYear(2020);
        }
        if (entity.getModeScope() == null || entity.getModeScope().isBlank()) {
            entity.setModeScope("ANY");
        } else {
            entity.setModeScope(entity.getModeScope().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName("Incoterms " + entity.getVersionYear());
        }
    }
    private void ensureUnique(Incoterm entity) {
        repository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getCode())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Incoterm already exists in this company");
            });
    }
    @Transactional(readOnly = true)
    public Page<IncotermResponse> search(IncotermSearchRequest request, Pageable pageable) {
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
    public IncotermResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public IncotermResponse create(IncotermCreateRequest dto) {
        Incoterm entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Incoterm saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public IncotermResponse update(UUID id, IncotermUpdateRequest dto) {
        Incoterm entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Incoterm saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Incoterm entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public IncotermResponse restore(UUID id) {
        Incoterm entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Incoterm saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Incoterm findEntity(UUID id) {
        Incoterm entity = repository.findByIdAndIsDeletedFalse(id)
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
