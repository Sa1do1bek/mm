package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierLetterResponse;
import com.enterprise.mm.payload.SupplierLetterUpdateRequest;
import com.enterprise.mm.payload.SupplierLetterCreateRequest;
import com.enterprise.mm.payload.SupplierLetterSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierLetterMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierLetter;
import com.enterprise.mm.repository.SupplierLetterRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierLetterService {
    private final SupplierLetterRepository repository;
    private final SupplierLetterMapper mapper;
    private final String entityName = "SupplierLetter";
    private final AuditService auditService;
    private final SupplierRepository supplierRepository;
    protected void beforeCreate(SupplierLetter entity, SupplierLetterCreateRequest dto) {
        validateSupplier(entity);
        if (isBlank(entity.getStatus())) {
            entity.setStatus("SENT");
        }
        if (isBlank(entity.getCode()) && !isBlank(entity.getLetterNumber())) {
            entity.setCode(entity.getLetterNumber());
        }
        if (isBlank(entity.getName())) {
            entity.setName(!isBlank(entity.getSubject()) ? entity.getSubject() : "Supplier letter");
        }
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
    protected void beforeUpdate(SupplierLetter entity, SupplierLetterUpdateRequest dto) {
        validateSupplier(entity);
        if (isBlank(entity.getName())) {
            entity.setName(!isBlank(entity.getSubject()) ? entity.getSubject() : "Supplier letter");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private Supplier validateSupplier(SupplierLetter entity) {
        if (entity.getSupplierId() == null) {
            throw new BusinessException("supplierId is required");
        }
        return supplierRepository.findByIdAndIsDeletedFalse(entity.getSupplierId())
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
    }
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    @Transactional(readOnly = true)
    public Page<SupplierLetterResponse> search(SupplierLetterSearchRequest request, Pageable pageable) {
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
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public SupplierLetterResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierLetterResponse create(SupplierLetterCreateRequest dto) {
        SupplierLetter entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierLetter saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierLetterResponse update(UUID id, SupplierLetterUpdateRequest dto) {
        SupplierLetter entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierLetter saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierLetter entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierLetterResponse restore(UUID id) {
        SupplierLetter entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierLetter saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SupplierLetter findEntity(UUID id) {
        SupplierLetter entity = repository.findByIdAndIsDeletedFalse(id)
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

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }
}
