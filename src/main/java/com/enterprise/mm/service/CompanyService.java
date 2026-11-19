package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.CompanyResponse;
import com.enterprise.mm.payload.CompanyUpdateRequest;
import com.enterprise.mm.payload.CompanyCreateRequest;
import com.enterprise.mm.payload.CompanySearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CompanyMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.repository.CompanyRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class CompanyService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final CompanyRepository repository;
    private final CompanyMapper mapper;
    private final String entityName = "Company";
    private final AuditService auditService;
    protected boolean companyScoped() {
        return false;
    }
    @Transactional(readOnly = true)
    public Page<CompanyResponse> search(CompanySearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        return repository.search(
            TenantContext.currentTenant(),
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            filter.fromDateTime(),
            filter.toDateTime(),
            request.companyId(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public CompanyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public CompanyResponse create(CompanyCreateRequest dto) {
        Company entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Company saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CompanyResponse update(UUID id, CompanyUpdateRequest dto) {
        Company entity = findEntity(id);
        guardOpeningImport(entity.getId());
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Company saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Company entity = findEntity(id);
        guardOpeningImport(entity.getId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public CompanyResponse restore(UUID id) {
        Company entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guardOpeningImport(entity.getId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Company saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Company findEntity(UUID id) {
        Company entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(Company entity, CompanyCreateRequest dto) {
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
        normalizeNonMovingStockSettings(entity);
    }
    protected void beforeUpdate(Company entity, CompanyUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalizeNonMovingStockSettings(entity);
    }

    protected void normalizeNonMovingStockSettings(Company entity) {
        if (entity.getNonMovingStockThresholdMonths() == null || entity.getNonMovingStockThresholdMonths() < 1) {
            entity.setNonMovingStockThresholdMonths(12);
        }
        if (entity.getNonMovingStockAutoDetectEnabled() == null) {
            entity.setNonMovingStockAutoDetectEnabled(Boolean.FALSE);
        }
    }
    public List<CompanyResponse> getCompanies(String tenantId) {
        return repository.findByTenantIdAndIsDeletedFalseOrderByNameAsc(tenantId).stream()
            .map(mapper::toResponse)
            .toList();
    }
}

