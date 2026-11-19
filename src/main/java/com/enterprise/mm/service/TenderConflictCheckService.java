package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.BusinessActionDtos.TenderConflictResolutionRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.TenderConflictCheckResponse;
import com.enterprise.mm.payload.TenderConflictCheckUpdateRequest;
import com.enterprise.mm.payload.TenderConflictCheckCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TenderConflictCheckMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TenderConflictCheck;
import com.enterprise.mm.repository.TenderConflictCheckRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.TenderConflictCheckSearchRequest;

@Service
@RequiredArgsConstructor
public class TenderConflictCheckService {
    private final String entityName = "TenderConflictCheck";
    private final TenderConflictCheckRepository repository;
    private final TenderConflictCheckMapper mapper;
    private final AuditService auditService;
    @Transactional
    public TenderConflictCheckResponse resolve(UUID id, TenderConflictResolutionRequest request) {
        TenderConflictCheck check = findEntity(id);
        if (!check.isConflictFound()) {
            throw new BusinessException("Only detected conflict can be resolved");
        }
        check.setResolved(true);
        check.setMitigationAction(request == null ? null : request.mitigationAction());
        check.setStatus("RESOLVED");
        TenderConflictCheck saved = repository.save(check);
        auditService.log("TenderConflictCheck", id, "RESOLVE", saved.getMitigationAction(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional(readOnly = true)
    public Page<TenderConflictCheckResponse> search(TenderConflictCheckSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            TenderConflictCheck.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public TenderConflictCheckResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public TenderConflictCheckResponse create(TenderConflictCheckCreateRequest dto) {
        TenderConflictCheck entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        TenderConflictCheck saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public TenderConflictCheckResponse update(UUID id, TenderConflictCheckUpdateRequest dto) {
        TenderConflictCheck entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        TenderConflictCheck saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        TenderConflictCheck entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public TenderConflictCheckResponse restore(UUID id) {
        TenderConflictCheck entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        TenderConflictCheck saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected TenderConflictCheck findEntity(UUID id) {
        TenderConflictCheck entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(TenderConflictCheck entity, TenderConflictCheckCreateRequest dto) {
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
    protected void beforeUpdate(TenderConflictCheck entity, TenderConflictCheckUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}

