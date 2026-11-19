package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.TenderBidOpeningProtocolResponse;
import com.enterprise.mm.payload.TenderBidOpeningProtocolUpdateRequest;
import com.enterprise.mm.payload.TenderBidOpeningProtocolCreateRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TenderBidOpeningProtocolMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import com.enterprise.mm.repository.TenderBidOpeningProtocolRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.TenderBidOpeningProtocolSearchRequest;

@Service
@RequiredArgsConstructor
public class TenderBidOpeningProtocolService {
    private final TenderBidOpeningProtocolRepository repository;
    private final TenderBidOpeningProtocolMapper mapper;
    private final String entityName = "TenderBidOpeningProtocol";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<TenderBidOpeningProtocolResponse> search(TenderBidOpeningProtocolSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            TenderBidOpeningProtocol.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public TenderBidOpeningProtocolResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public TenderBidOpeningProtocolResponse create(TenderBidOpeningProtocolCreateRequest dto) {
        TenderBidOpeningProtocol entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        TenderBidOpeningProtocol saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public TenderBidOpeningProtocolResponse update(UUID id, TenderBidOpeningProtocolUpdateRequest dto) {
        TenderBidOpeningProtocol entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        TenderBidOpeningProtocol saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        TenderBidOpeningProtocol entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public TenderBidOpeningProtocolResponse restore(UUID id) {
        TenderBidOpeningProtocol entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        TenderBidOpeningProtocol saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected TenderBidOpeningProtocol findEntity(UUID id) {
        TenderBidOpeningProtocol entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(TenderBidOpeningProtocol entity, TenderBidOpeningProtocolCreateRequest dto) {
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
    protected void beforeUpdate(TenderBidOpeningProtocol entity, TenderBidOpeningProtocolUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}

