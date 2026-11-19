package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.LandedCostAllocationLineResponse;
import com.enterprise.mm.payload.LandedCostAllocationLineUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineCreateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.LandedCostAllocationLineMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.LandedCostAllocation;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import com.enterprise.mm.repository.LandedCostAllocationLineRepository;
import com.enterprise.mm.repository.LandedCostAllocationRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LandedCostAllocationLineService {
    private final LandedCostAllocationLineRepository repository;
    private final LandedCostAllocationLineMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "LandedCostAllocationLine";
    private final AuditService auditService;
    private final LandedCostAllocationRepository allocationRepository;

    protected void beforeCreate(LandedCostAllocationLine entity, LandedCostAllocationLineCreateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
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

        entity.setStatus("ACTIVE");
        normalize(entity);
    }
    protected void beforeUpdate(LandedCostAllocationLine entity, LandedCostAllocationLineUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    private void normalize(LandedCostAllocationLine entity) {
        requireDraftAllocation(entity.getAllocationDocumentId());
        if (entity.getMaterialId() == null || entity.getWarehouseId() == null) {
            throw new BusinessException("Material and warehouse are required for landed cost allocation line");
        }
        entity.setAllocationBase(nvl(entity.getAllocationBase()));
        entity.setQuantity(nvl(entity.getQuantity()));
        entity.setAllocatedAmount(nvl(entity.getAllocatedAmount()));
        entity.setAllocatedBaseAmount(nvl(entity.getAllocatedBaseAmount()));
        entity.setUnitCostIncrease(nvl(entity.getUnitCostIncrease()));
        if (entity.getAllocationBase().signum() < 0 || entity.getQuantity().signum() < 0 || entity.getAllocatedAmount().signum() < 0 || entity.getAllocatedBaseAmount().signum() < 0) {
            throw new BusinessException("Landed cost allocation line amounts cannot be negative");
        }
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<LandedCostAllocationLineResponse> search(LandedCostAllocationLineSearchRequest request, Pageable pageable) {
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
            filter.warehouseId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public LandedCostAllocationLineResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public LandedCostAllocationLineResponse create(LandedCostAllocationLineCreateRequest dto) {
        LandedCostAllocationLine entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        LandedCostAllocationLine saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LandedCostAllocationLineResponse update(UUID id, LandedCostAllocationLineUpdateRequest dto) {
        LandedCostAllocationLine entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireDraftAllocation(entity.getAllocationDocumentId());
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        LandedCostAllocationLine saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        LandedCostAllocationLine entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireDraftAllocation(entity.getAllocationDocumentId());
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public LandedCostAllocationLineResponse restore(UUID id) {
        LandedCostAllocationLine entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireDraftAllocation(entity.getAllocationDocumentId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        LandedCostAllocationLine saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireDraftAllocation(UUID allocationDocumentId) {
        LandedCostAllocation allocation = allocationRepository.findByIdAndIsDeletedFalse(allocationDocumentId)
            .orElseThrow(() -> new BusinessException("Landed cost allocation not found: " + allocationDocumentId));
        if (allocation.isDeleted() || !TenantContext.currentTenant().equals(allocation.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(allocation.getCompanyId())) {
            throw new BusinessException("Landed cost allocation not found: " + allocationDocumentId);
        }
        if (!"DRAFT".equals(allocation.getStatus())) {
            throw new BusinessException("Lines can be edited only for DRAFT landed cost allocation");
        }
    }
    protected LandedCostAllocationLine findEntity(UUID id) {
        LandedCostAllocationLine entity = repository.findByIdAndIsDeletedFalse(id)
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
