package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.ManufacturerResponse;
import com.enterprise.mm.payload.ManufacturerUpdateRequest;
import com.enterprise.mm.payload.ManufacturerCreateRequest;
import com.enterprise.mm.payload.ManufacturerSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ManufacturerMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.repository.ManufacturerRepository;
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
public class ManufacturerService {
    private final ManufacturerRepository repository;
    private final ManufacturerMapper mapper;
    private final String entityName = "Manufacturer";
    private final AuditService auditService;

    protected void beforeCreate(Manufacturer entity, ManufacturerCreateRequest dto) {
        normalize(entity);
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
    protected void beforeUpdate(Manufacturer entity, ManufacturerUpdateRequest dto) {
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void normalize(Manufacturer entity) {
        entity.setName(trim(entity.getName()));
        entity.setCode(upper(trim(entity.getCode())));
        entity.setCountry(trim(entity.getCountry()));
        entity.setCountryCode(upper(trim(entity.getCountryCode())));
        entity.setWebsite(trim(entity.getWebsite()));
        entity.setEmail(lower(trim(entity.getEmail())));
        entity.setPhone(trim(entity.getPhone()));
        if ((entity.getCode() == null || entity.getCode().isBlank()) && entity.getName() != null && !entity.getName().isBlank()) {
            entity.setCode(codeFromName(entity.getName()));
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
    }
    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
    private String codeFromName(String name) {
        String normalized = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "MANUFACTURER";
        }
        String base = normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
        String candidate = base;
        int suffix = 2;
        while (repository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), candidate).isPresent()) {
            String tail = "_" + suffix++;
            candidate = base.length() + tail.length() > 80 ? base.substring(0, 80 - tail.length()) + tail : base + tail;
        }
        return candidate;
    }
    @Transactional(readOnly = true)
    public Page<ManufacturerResponse> search(ManufacturerSearchRequest request, Pageable pageable) {
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
    public ManufacturerResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ManufacturerResponse create(ManufacturerCreateRequest dto) {
        Manufacturer entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Manufacturer saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ManufacturerResponse update(UUID id, ManufacturerUpdateRequest dto) {
        Manufacturer entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Manufacturer saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Manufacturer entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ManufacturerResponse restore(UUID id) {
        Manufacturer entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Manufacturer saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Manufacturer findEntity(UUID id) {
        Manufacturer entity = repository.findByIdAndIsDeletedFalse(id)
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

