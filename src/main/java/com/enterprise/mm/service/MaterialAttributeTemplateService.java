package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialAttributeTemplate;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialAttributeTemplateMapper;
import com.enterprise.mm.payload.MaterialAttributeTemplateCreateRequest;
import com.enterprise.mm.payload.MaterialAttributeTemplateResponse;
import com.enterprise.mm.payload.MaterialAttributeTemplateUpdateRequest;
import com.enterprise.mm.payload.MaterialAttributeTemplateSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MaterialAttributeTemplateService {
    private static final Set<String> DATA_TYPES = Set.of("STRING", "NUMBER", "DATE", "BOOLEAN", "ENUM", "TEXT");

    private final MaterialAttributeTemplateRepository repository;
    private final MaterialAttributeTemplateMapper mapper;
    private final MaterialGroupRepository materialGroupRepository;
    private final AuditService auditService;
    private final String entityName = "MaterialAttributeTemplate";

    @Transactional(readOnly = true)
    public Page<MaterialAttributeTemplateResponse> search(MaterialAttributeTemplateSearchRequest request, Pageable pageable) {
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
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialAttributeTemplateResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialAttributeTemplateResponse create(MaterialAttributeTemplateCreateRequest dto) {
        MaterialAttributeTemplate entity = mapper.toEntity(dto);
        beforeCreate(entity);
        MaterialAttributeTemplate saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialAttributeTemplateResponse update(UUID id, MaterialAttributeTemplateUpdateRequest dto) {
        MaterialAttributeTemplate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        MaterialAttributeTemplate saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialAttributeTemplate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialAttributeTemplateResponse restore(UUID id) {
        MaterialAttributeTemplate entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialAttributeTemplate saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected void beforeCreate(MaterialAttributeTemplate entity) {
        defaultBaseFields(entity);
        normalize(entity);
    }

    protected void beforeUpdate(MaterialAttributeTemplate entity) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalize(entity);
    }

    protected MaterialAttributeTemplate findEntity(UUID id) {
        MaterialAttributeTemplate entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(MaterialAttributeTemplate entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (companyScoped() && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private void normalize(MaterialAttributeTemplate entity) {
        materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getMaterialGroupId())
            .orElseThrow(() -> new BusinessException("Material group not found: " + entity.getMaterialGroupId()));
        if (entity.getAttributeName() == null || entity.getAttributeName().isBlank()) {
            throw new BusinessException("Attribute name is required");
        }
        entity.setDataType(entity.getDataType() == null ? null : entity.getDataType().trim().toUpperCase(Locale.ROOT));
        if (!DATA_TYPES.contains(entity.getDataType())) {
            throw new BusinessException("Unsupported data type: " + entity.getDataType());
        }
        if (entity.getRequired() == null) {
            entity.setRequired(Boolean.FALSE);
        }
    }

    private void defaultBaseFields(MaterialAttributeTemplate entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getAttributeName() == null || entity.getAttributeName().isBlank() ? "Material attribute" : entity.getAttributeName());
        }
        if (entity.getDataType() == null || entity.getDataType().isBlank()) {
            entity.setDataType("STRING");
        }
    }
}
