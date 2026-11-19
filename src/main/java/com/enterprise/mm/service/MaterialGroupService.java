package com.enterprise.mm.service;
import com.enterprise.mm.payload.MaterialGroupCreateRequest;
import com.enterprise.mm.payload.MaterialGroupResponse;
import com.enterprise.mm.payload.MaterialGroupUpdateRequest;
import com.enterprise.mm.payload.MaterialGroupSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.MaterialGroupStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialGroupMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.repository.MaterialGroupRepository;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialGroupService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }


    private final MaterialGroupRepository repository;
    private final MaterialGroupMapper mapper;
    private final MaterialGroupUsageService materialGroupUsageService;
    private final CodeGeneratorService codeGeneratorService;
    private final String entityName = "MaterialGroup";
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<MaterialGroupResponse> search(MaterialGroupSearchRequest request, Pageable pageable) {
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
    public MaterialGroupResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialGroupResponse create(MaterialGroupCreateRequest dto) {
        MaterialGroup entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        MaterialGroup saved = repository.save(entity);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialGroupResponse update(UUID id, MaterialGroupUpdateRequest dto) {
        MaterialGroup entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        String currentCode = entity.getCode();
        String currentStatus = entity.getStatus();
        mapper.updateEntity(entity, dto);
        if (dto.getCode() == null) {
            entity.setCode(currentCode);
        }
        if (dto.getStatus() == null) {
            entity.setStatus(currentStatus);
        }
        beforeUpdate(entity, dto);
        MaterialGroup saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialGroup entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        if (materialGroupUsageService.isUsed(entity)) {
            throw new BusinessException("Material group is already used by materials or business documents. Deactivate it instead.");
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialGroupResponse restore(UUID id) {
        MaterialGroup entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialGroup saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected MaterialGroup findEntity(UUID id) {
        MaterialGroup entity = repository.findByIdAndIsDeletedFalse(id)
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

    protected void beforeCreate(MaterialGroup entity, MaterialGroupCreateRequest dto) {
        normalize(entity);
        if (entity.getTenantId() == null || entity.getTenantId().isBlank()) {
            entity.setTenantId(TenantContext.currentTenant());
        }

        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }

        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(codeGeneratorService.generateNextCode("MATERIAL_GROUP").code());
        }
        validate(entity, null);
    }

    protected void beforeUpdate(MaterialGroup entity, MaterialGroupUpdateRequest dto) {
        normalize(entity);
        validate(entity, entity.getId());
    }

    protected boolean companyScoped() {
        return true;
    }
    private void normalize(MaterialGroup entity) {
        entity.setName(trim(entity.getName()));
        entity.setCode(normalizeCode(entity.getCode()));
        entity.setDescription(trim(entity.getDescription()));
        entity.setStatus(normalizeStatus(entity.getStatus(), MaterialGroupStatus.ACTIVE));
    }

    private void validate(MaterialGroup entity, UUID materialGroupId) {
        if (entity.getName() == null) {
            throw new BadRequestException("Material group name is required");
        }
        if (entity.getCode() != null && codeExists(entity, materialGroupId)) {
            throw new BusinessException("Material group code already exists in the current company");
        }
    }

    private boolean codeExists(MaterialGroup entity, UUID materialGroupId) {
        return materialGroupId == null
            ? repository.existsByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(entity.getTenantId(), entity.getCompanyId(), entity.getCode())
            : repository.existsByTenantIdAndCompanyIdAndCodeAndIdNotAndIsDeletedFalse(entity.getTenantId(), entity.getCompanyId(), entity.getCode(), materialGroupId);
    }

    private String normalizeStatus(String status, MaterialGroupStatus defaultStatus) {
        String normalized = trim(status);
        if (normalized == null) {
            if (defaultStatus == null) {
                throw new BadRequestException("Material group status must be ACTIVE or INACTIVE");
            }
            return defaultStatus.name();
        }
        try {
            return MaterialGroupStatus.valueOf(normalized.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Material group status must be ACTIVE or INACTIVE");
        }
    }

    private String normalizeCode(String code) {
        String normalized = trim(code);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

