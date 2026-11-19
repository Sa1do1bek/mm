package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.MaterialEquipmentCompatibilityResponse;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityUpdateRequest;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityCreateRequest;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilitySearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialEquipmentCompatibilityMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialCompatibilityType;
import com.enterprise.mm.entity.MaterialEquipmentCompatibility;
import com.enterprise.mm.repository.MaterialEquipmentCompatibilityRepository;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialEquipmentCompatibilityService {
    private final MaterialEquipmentCompatibilityRepository repository;
    private final MaterialEquipmentCompatibilityMapper mapper;
    private final String entityName = "MaterialEquipmentCompatibility";
    private final AuditService auditService;
    private final MaterialCatalogGuard catalogGuard;

    protected void beforeCreate(MaterialEquipmentCompatibility entity, MaterialEquipmentCompatibilityCreateRequest dto) {
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

        normalize(entity, null);
    }
    protected void beforeUpdate(MaterialEquipmentCompatibility entity, MaterialEquipmentCompatibilityUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity, entity.getId());
    }
    private void normalize(MaterialEquipmentCompatibility entity, UUID excludeId) {
        catalogGuard.requireUsableMaterial(entity.getMaterialId(), "Material");
        catalogGuard.requireEquipment(entity.getEquipmentId());
        if (entity.getCompatibilityType() == null || entity.getCompatibilityType().isBlank()) {
            entity.setCompatibilityType(MaterialCompatibilityType.SPARE_PART.name());
        } else {
            try {
                entity.setCompatibilityType(MaterialCompatibilityType.valueOf(
                    entity.getCompatibilityType().trim().toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("Unsupported compatibility type: " + entity.getCompatibilityType());
            }
        }
        if (entity.getValidFrom() == null) {
            entity.setValidFrom(LocalDate.now());
        }
        if (entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("Valid to must be after valid from");
        }
        if (repository.existsActiveBusinessKey(
            TenantContext.currentTenant(), entity.getCompanyId(), entity.getMaterialId(),
            entity.getEquipmentId(), entity.getCompatibilityType(), excludeId)) {
            throw new BusinessException("Material/equipment compatibility relationship already exists");
        }
    }
    @Transactional(readOnly = true)
    public Page<MaterialEquipmentCompatibilityResponse> search(MaterialEquipmentCompatibilitySearchRequest request, Pageable pageable) {
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
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public MaterialEquipmentCompatibilityResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public MaterialEquipmentCompatibilityResponse create(MaterialEquipmentCompatibilityCreateRequest dto) {
        MaterialEquipmentCompatibility entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        MaterialEquipmentCompatibility saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public MaterialEquipmentCompatibilityResponse update(UUID id, MaterialEquipmentCompatibilityUpdateRequest dto) {
        MaterialEquipmentCompatibility entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        MaterialEquipmentCompatibility saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        MaterialEquipmentCompatibility entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public MaterialEquipmentCompatibilityResponse restore(UUID id) {
        MaterialEquipmentCompatibility entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        normalize(entity, entity.getId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialEquipmentCompatibility saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected MaterialEquipmentCompatibility findEntity(UUID id) {
        MaterialEquipmentCompatibility entity = repository.findByIdAndIsDeletedFalse(id)
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
