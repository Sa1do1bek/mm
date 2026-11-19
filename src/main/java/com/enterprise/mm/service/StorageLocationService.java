package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StorageLocationResponse;
import com.enterprise.mm.payload.StorageLocationUpdateRequest;
import com.enterprise.mm.payload.StorageLocationCreateRequest;
import com.enterprise.mm.payload.StorageLocationSearchRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StorageLocationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.repository.StorageLocationRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StorageLocationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final StorageLocationRepository repository;
    private final StorageLocationMapper mapper;
    private final String entityName = "StorageLocation";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional(readOnly = true)
    public Page<StorageLocationResponse> search(StorageLocationSearchRequest request, Pageable pageable) {
        StorageLocationSearchRequest criteria = request == null ? StorageLocationSearchRequest.empty() : request;
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(criteria);
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
            criteria.id(),
            criteria.code(),
            criteria.name(),
            criteria.description(),
            criteria.deleted(),
            criteria.createdBy(),
            criteria.updatedBy(),
            criteria.version(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            criteria.storageZoneType(),
            criteria.zoneCode(),
            criteria.binCode(),
            criteria.barcode(),
            criteria.qrCode(),
            criteria.rfidTag(),
            criteria.temperatureZone(),
            criteria.hazardClassAllowed(),
            criteria.capacityQuantity(),
            criteria.maxWeight(),
            criteria.allowMixedMaterials(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public StorageLocationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public StorageLocationResponse create(StorageLocationCreateRequest dto) {
        StorageLocation entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        StorageLocation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public StorageLocationResponse update(UUID id, StorageLocationUpdateRequest dto) {
        StorageLocation entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        StorageLocation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        StorageLocation entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public StorageLocationResponse restore(UUID id) {
        StorageLocation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guardOpeningImport(entity.getCompanyId());
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        StorageLocation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected StorageLocation findEntity(UUID id) {
        StorageLocation entity = repository.findByIdAndIsDeletedFalse(id)
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
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }
    protected void beforeCreate(StorageLocation entity, StorageLocationCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
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
    protected void beforeUpdate(StorageLocation entity, StorageLocationUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}
