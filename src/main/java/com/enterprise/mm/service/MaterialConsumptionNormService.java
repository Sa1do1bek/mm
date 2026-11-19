package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.MaterialConsumptionNormResponse;
import com.enterprise.mm.payload.MaterialConsumptionNormUpdateRequest;
import com.enterprise.mm.payload.MaterialConsumptionNormCreateRequest;
import com.enterprise.mm.payload.MaterialConsumptionNormSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialConsumptionNormMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ConsumptionNormType;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import com.enterprise.mm.repository.MaterialConsumptionNormRepository;
import java.math.BigDecimal;
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
public class MaterialConsumptionNormService {
    private final MaterialConsumptionNormRepository repository;
    private final MaterialConsumptionNormMapper mapper;
    private final String entityName = "MaterialConsumptionNorm";
    private final AuditService auditService;
    private final MaterialCatalogGuard catalogGuard;
    private final RoleDataScopeService roleDataScopeService;

    protected void beforeCreate(MaterialConsumptionNorm entity, MaterialConsumptionNormCreateRequest dto) {
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
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Material consumption norm");
    }
    protected void beforeUpdate(MaterialConsumptionNorm entity, MaterialConsumptionNormUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity, entity.getId());
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Material consumption norm");
    }
    private void normalize(MaterialConsumptionNorm entity, UUID excludeId) {
        catalogGuard.requireUsableMaterial(entity.getMaterialId(), "Material");
        if (entity.getEquipmentId() != null) {
            catalogGuard.requireEquipment(entity.getEquipmentId());
        }
        if (entity.getNormType() == null || entity.getNormType().isBlank()) {
            entity.setNormType(ConsumptionNormType.PRODUCTION.name());
        } else {
            try {
                entity.setNormType(ConsumptionNormType.valueOf(
                    entity.getNormType().trim().toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("Unsupported consumption norm type: " + entity.getNormType());
            }
        }
        entity.setProductCode(normalizeKey(entity.getProductCode()));
        entity.setOperationCode(normalizeKey(entity.getOperationCode()));
        entity.setBaseQuantity(entity.getBaseQuantity() == null ? BigDecimal.ZERO : entity.getBaseQuantity());
        entity.setOutputQuantity(entity.getOutputQuantity() == null ? BigDecimal.ONE : entity.getOutputQuantity());
        entity.setLossPercent(entity.getLossPercent() == null ? BigDecimal.ZERO : entity.getLossPercent());
        if (entity.getBaseQuantity().signum() <= 0) {
            throw new BusinessException("Base quantity must be positive");
        }
        if (entity.getOutputQuantity().signum() <= 0) {
            throw new BusinessException("Output quantity must be positive");
        }
        if (entity.getLossPercent().signum() < 0 || entity.getLossPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("Loss percent must be between 0 and 100");
        }
        if (entity.getEffectiveFrom() == null) {
            entity.setEffectiveFrom(LocalDate.now());
        }
        if (entity.getEffectiveTo() != null && entity.getEffectiveTo().isBefore(entity.getEffectiveFrom())) {
            throw new BusinessException("Effective to must be after effective from");
        }
        if ("ACTIVE".equalsIgnoreCase(entity.getStatus()) && repository.existsOverlappingActiveNorm(
            TenantContext.currentTenant(), entity.getCompanyId(), entity.getMaterialId(),
            entity.getEquipmentId(), entity.getDepartmentId(), entity.getProductCode(),
            entity.getOperationCode(), entity.getNormType(), entity.getEffectiveFrom(),
            entity.getEffectiveTo(), excludeId)) {
            throw new BusinessException("Material consumption norm overlaps an existing effective range");
        }
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
    @Transactional(readOnly = true)
    public Page<MaterialConsumptionNormResponse> search(MaterialConsumptionNormSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var records = repository.search(
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
            dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable
        );
        if (!dataScope.hasDocumentScope()) {
            return records.map(mapper::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId()),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public MaterialConsumptionNormResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public MaterialConsumptionNormResponse create(MaterialConsumptionNormCreateRequest dto) {
        MaterialConsumptionNorm entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        MaterialConsumptionNorm saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public MaterialConsumptionNormResponse update(UUID id, MaterialConsumptionNormUpdateRequest dto) {
        MaterialConsumptionNorm entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        MaterialConsumptionNorm saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        MaterialConsumptionNorm entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public MaterialConsumptionNormResponse restore(UUID id) {
        MaterialConsumptionNorm entity = repository.findAnyById(id)
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
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        normalize(entity, entity.getId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialConsumptionNorm saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected MaterialConsumptionNorm findEntity(UUID id) {
        MaterialConsumptionNorm entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}
