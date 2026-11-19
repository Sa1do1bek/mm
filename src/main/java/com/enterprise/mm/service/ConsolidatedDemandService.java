package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ConsolidatedDemandResponse;
import com.enterprise.mm.payload.ConsolidatedDemandUpdateRequest;
import com.enterprise.mm.payload.ConsolidatedDemandCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ConsolidatedDemandMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ConsolidatedDemandStatus;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.repository.ConsolidatedDemandRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ConsolidatedDemandSearchRequest;

@Service
@RequiredArgsConstructor
public class ConsolidatedDemandService {
    private final ConsolidatedDemandRepository repository;
    private final ConsolidatedDemandMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "ConsolidatedDemand";
    private final AuditService auditService;
    protected void beforeCreate(ConsolidatedDemand entity, ConsolidatedDemandCreateRequest dto) {
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(ConsolidatedDemandStatus.OPEN.name());
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
    @Transactional(readOnly = true)
    public Page<ConsolidatedDemandResponse> search(ConsolidatedDemandSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ConsolidatedDemand.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ConsolidatedDemandResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ConsolidatedDemandResponse create(ConsolidatedDemandCreateRequest dto) {
        ConsolidatedDemand entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ConsolidatedDemand saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ConsolidatedDemandResponse update(UUID id, ConsolidatedDemandUpdateRequest dto) {
        ConsolidatedDemand entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireOpen(entity);
        String status = entity.getStatus();
        java.math.BigDecimal totalRequestedQuantity = entity.getTotalRequestedQuantity();
        java.math.BigDecimal totalApprovedQuantity = entity.getTotalApprovedQuantity();
        java.math.BigDecimal totalEstimatedAmount = entity.getTotalEstimatedAmount();
        Integer sourceItemCount = entity.getSourceItemCount();
        String highestPriority = entity.getHighestPriority();
        UUID purchaseRequestId = entity.getPurchaseRequestId();
        java.time.LocalDateTime consolidatedAt = entity.getConsolidatedAt();
        mapper.updateEntity(entity, dto);
        entity.setStatus(status);
        entity.setTotalRequestedQuantity(totalRequestedQuantity);
        entity.setTotalApprovedQuantity(totalApprovedQuantity);
        entity.setTotalEstimatedAmount(totalEstimatedAmount);
        entity.setSourceItemCount(sourceItemCount);
        entity.setHighestPriority(highestPriority);
        entity.setPurchaseRequestId(purchaseRequestId);
        entity.setConsolidatedAt(consolidatedAt);
        beforeUpdate(entity, dto);
        ConsolidatedDemand saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ConsolidatedDemand entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireOpen(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ConsolidatedDemandResponse restore(UUID id) {
        ConsolidatedDemand entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireOpen(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ConsolidatedDemand saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ConsolidatedDemand findEntity(UUID id) {
        ConsolidatedDemand entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeUpdate(ConsolidatedDemand entity, ConsolidatedDemandUpdateRequest dto) {
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void requireOpen(ConsolidatedDemand entity) {
        if (!ConsolidatedDemandStatus.OPEN.name().equals(entity.getStatus())) {
            throw new BusinessException("Consolidated demand must be OPEN, current status is " + entity.getStatus());
        }
    }
}
