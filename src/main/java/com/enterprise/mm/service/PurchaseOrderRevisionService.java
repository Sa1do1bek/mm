package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.PurchaseOrderRevisionDto;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseOrderRevisionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrderRevision;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.PurchaseOrderRevisionSearchRequest;

@Service
@RequiredArgsConstructor
public class PurchaseOrderRevisionService {
    private final PurchaseOrderRevisionRepository repository;
    private final PurchaseOrderRevisionMapper mapper;
    private final String entityName = "PurchaseOrderRevision";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<PurchaseOrderRevisionDto> search(PurchaseOrderRevisionSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            PurchaseOrderRevision.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toDto);
    }
    @Transactional(readOnly = true)
    public PurchaseOrderRevisionDto get(UUID id) {
        return mapper.toDto(findEntity(id));
    }
    @Transactional
    public PurchaseOrderRevisionDto create(PurchaseOrderRevisionDto dto) {
        PurchaseOrderRevision entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        PurchaseOrderRevision saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toDto(saved);
    }
    @Transactional
    public PurchaseOrderRevisionDto update(UUID id, PurchaseOrderRevisionDto dto) {
        PurchaseOrderRevision entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        PurchaseOrderRevision saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toDto(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PurchaseOrderRevision entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PurchaseOrderRevisionDto restore(UUID id) {
        PurchaseOrderRevision entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PurchaseOrderRevision saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toDto(saved);
    }
    protected PurchaseOrderRevision findEntity(UUID id) {
        PurchaseOrderRevision entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(PurchaseOrderRevision entity, PurchaseOrderRevisionDto dto) {
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
    protected void beforeUpdate(PurchaseOrderRevision entity, PurchaseOrderRevisionDto dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}
