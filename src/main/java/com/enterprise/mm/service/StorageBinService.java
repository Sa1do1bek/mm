package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StorageBinMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StorageBinCreateRequest;
import com.enterprise.mm.payload.StorageBinResponse;
import com.enterprise.mm.payload.StorageBinUpdateRequest;
import com.enterprise.mm.payload.StorageBinSearchRequest;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.math.BigDecimal;
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
public class StorageBinService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final StorageBinRepository repository;
    private final StorageLocationRepository storageLocationRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final StorageBinMapper mapper;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final String entityName = "StorageBin";

    @Transactional(readOnly = true)
    public Page<StorageBinResponse> search(StorageBinSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
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
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            request.storageLocationId(),
            request.storageZoneType(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public StorageBinResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public StorageBinResponse create(StorageBinCreateRequest dto) {
        StorageBin entity = mapper.toEntity(dto);
        beforeSave(entity);
        StorageBin saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StorageBinResponse update(UUID id, StorageBinUpdateRequest dto) {
        StorageBin entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeSave(entity);
        StorageBin saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        StorageBin entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public StorageBinResponse restore(UUID id) {
        StorageBin entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantAndCompany(entity, id);
        requireWarehouseAccess(entity.getWarehouseId(), id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        StorageBin saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected StorageBin findEntity(UUID id) {
        StorageBin entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateReadable(entity, id);
        return entity;
    }

    private void beforeSave(StorageBin entity) {
        StorageLocation location = storageLocationRepository.findByIdAndIsDeletedFalse(entity.getStorageLocationId())
            .orElseThrow(() -> new NotFoundException("StorageLocation not found: " + entity.getStorageLocationId()));
        if (!TenantContext.currentTenant().equals(location.getTenantId())) {
            throw new NotFoundException("StorageLocation not found: " + entity.getStorageLocationId());
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(location.getCompanyId())) {
            throw new NotFoundException("StorageLocation not found: " + entity.getStorageLocationId());
        }
        requireWarehouseAccess(location.getWarehouseId(), entity.getId());
        if (entity.getWarehouseId() != null && !Objects.equals(entity.getWarehouseId(), location.getWarehouseId())) {
            throw new BusinessException("Storage bin warehouse must match storage location warehouse");
        }
        entity.setWarehouseId(location.getWarehouseId());
        entity.setCompanyId(location.getCompanyId());
        if (entity.getCapacityUomId() != null) {
            entity.setCapacityUom(unitOfMeasureRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), location.getCompanyId(), entity.getCapacityUomId())
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + entity.getCapacityUomId())));
        } else {
            entity.setCapacityUom(null);
        }
        if (entity.getCapacityQuantity() != null && entity.getCapacityQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Storage bin capacity must be greater than zero");
        }
        if ((entity.getCapacityQuantity() == null) != (entity.getCapacityUomId() == null)) {
            throw new BusinessException("Storage bin capacity requires both a value and a unit");
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getBinCode() == null || entity.getBinCode().isBlank()) {
            entity.setBinCode(entity.getCode());
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName("Storage Bin");
        }
    }

    private void validateReadable(StorageBin entity, UUID id) {
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        validateTenantAndCompany(entity, id);
        requireWarehouseAccess(entity.getWarehouseId(), id);
    }

    private void validateTenantAndCompany(StorageBin entity, UUID id) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
    }

    private void requireWarehouseAccess(UUID warehouseId, UUID id) {
        if (!roleDataScopeService.canAccessWarehouse(warehouseId)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
    }

    protected boolean companyScoped() {
        return true;
    }
}
