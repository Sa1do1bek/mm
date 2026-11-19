package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.MaterialAnalogResponse;
import com.enterprise.mm.payload.MaterialAnalogUpdateRequest;
import com.enterprise.mm.payload.MaterialAnalogCreateRequest;
import com.enterprise.mm.payload.MaterialAnalogSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialAnalogMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialAnalogType;
import com.enterprise.mm.entity.MaterialAnalog;
import com.enterprise.mm.repository.MaterialAnalogRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialAnalogService {
    private final MaterialAnalogRepository repository;
    private final MaterialAnalogMapper mapper;
    private final String entityName = "MaterialAnalog";
    private final AuditService auditService;
    private final MaterialCatalogGuard catalogGuard;

    protected void beforeCreate(MaterialAnalog entity, MaterialAnalogCreateRequest dto) {
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
    protected void beforeUpdate(MaterialAnalog entity, MaterialAnalogUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity, entity.getId());
    }
    private void normalize(MaterialAnalog entity, UUID excludeId) {
        catalogGuard.requireUsableMaterial(entity.getMaterialId(), "Material");
        catalogGuard.requireUsableMaterial(entity.getAnalogMaterialId(), "Analog material");
        if (entity.getMaterialId().equals(entity.getAnalogMaterialId())) {
            throw new BusinessException("Material cannot be its own analog");
        }
        if (entity.getAnalogType() == null || entity.getAnalogType().isBlank()) {
            entity.setAnalogType(MaterialAnalogType.SUBSTITUTE.name());
        } else {
            try {
                entity.setAnalogType(MaterialAnalogType.valueOf(
                    entity.getAnalogType().trim().toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("Unsupported analog type: " + entity.getAnalogType());
            }
        }
        entity.setConversionFactor(entity.getConversionFactor() == null ? BigDecimal.ONE : entity.getConversionFactor());
        if (entity.getConversionFactor().signum() <= 0) {
            throw new BusinessException("Conversion factor must be positive");
        }
        if (entity.getPriority() == null || entity.getPriority() <= 0) {
            entity.setPriority(100);
        }
        if (entity.getValidFrom() == null) {
            entity.setValidFrom(LocalDate.now());
        }
        if (entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("Valid to must be after valid from");
        }
        if (repository.existsActivePair(
            TenantContext.currentTenant(), entity.getCompanyId(), entity.getMaterialId(),
            entity.getAnalogMaterialId(), excludeId)) {
            throw new BusinessException("Material analog relationship already exists");
        }
        if (createsCycle(entity.getMaterialId(), entity.getAnalogMaterialId(), entity.getCompanyId())) {
            throw new BusinessException("Material analog relationship would create a cycle");
        }
    }

    private boolean createsCycle(UUID source, UUID target, UUID companyId) {
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            if (source.equals(current)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            repository.findActiveOutgoing(TenantContext.currentTenant(), companyId, current)
                .stream()
                .map(MaterialAnalog::getAnalogMaterialId)
                .filter(Objects::nonNull)
                .forEach(pending::addLast);
        }
        return false;
    }
    @Transactional(readOnly = true)
    public Page<MaterialAnalogResponse> search(MaterialAnalogSearchRequest request, Pageable pageable) {
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
    public MaterialAnalogResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public MaterialAnalogResponse create(MaterialAnalogCreateRequest dto) {
        MaterialAnalog entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        MaterialAnalog saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public MaterialAnalogResponse update(UUID id, MaterialAnalogUpdateRequest dto) {
        MaterialAnalog entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        MaterialAnalog saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        MaterialAnalog entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public MaterialAnalogResponse restore(UUID id) {
        MaterialAnalog entity = repository.findAnyById(id)
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
        MaterialAnalog saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected MaterialAnalog findEntity(UUID id) {
        MaterialAnalog entity = repository.findByIdAndIsDeletedFalse(id)
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
