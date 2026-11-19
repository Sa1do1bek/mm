package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ClassifierResponse;
import com.enterprise.mm.payload.ClassifierUpdateRequest;
import com.enterprise.mm.payload.ClassifierCreateRequest;
import com.enterprise.mm.payload.ClassifierSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ClassifierMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Classifier;
import com.enterprise.mm.repository.ClassifierRepository;
import com.enterprise.mm.repository.ClassifierTypeRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClassifierService {
    private static final String PROTECTED_VALUATION_METHOD_TYPE = "VALUATION_METHOD";
    private final ClassifierRepository repository;
    private final ClassifierMapper mapper;
    private final String entityName = "Classifier";
    private final AuditService auditService;
    private final ClassifierTypeRepository classifierTypeRepository;

    protected void beforeCreate(Classifier entity, ClassifierCreateRequest dto) {
        rejectProtectedType(entity.getClassifierType());
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

        normalizeAndValidate(entity);
    }
    protected void beforeUpdate(Classifier entity, ClassifierUpdateRequest dto) {
        rejectProtectedType(entity.getClassifierType());
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeAndValidate(entity);
    }
    private void normalizeAndValidate(Classifier entity) {
        if (entity.getClassifierType() == null || entity.getClassifierType().isBlank()) {
            throw new BadRequestException("Classifier type is required");
        }
        entity.setClassifierType(entity.getClassifierType().trim().toUpperCase(Locale.ROOT));
        classifierTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), entity.getClassifierType())
            .orElseThrow(() -> new BadRequestException("Unknown classifier type: " + entity.getClassifierType()));
    }

    private void rejectProtectedType(String classifierType) {
        if (classifierType != null
            && PROTECTED_VALUATION_METHOD_TYPE.equals(classifierType.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(
                "Inventory valuation method records cannot be mutated through generic classifiers");
        }
    }
    @Transactional(readOnly = true)
    public Page<ClassifierResponse> search(ClassifierSearchRequest request, Pageable pageable) {
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
            null,
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ClassifierResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ClassifierResponse create(ClassifierCreateRequest dto) {
        Classifier entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Classifier saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ClassifierResponse update(UUID id, ClassifierUpdateRequest dto) {
        Classifier entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rejectProtectedType(entity.getClassifierType());
        rejectProtectedType(dto == null ? null : dto.getClassifierType());
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Classifier saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Classifier entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rejectProtectedType(entity.getClassifierType());
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ClassifierResponse restore(UUID id) {
        Classifier entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        rejectProtectedType(entity.getClassifierType());
        normalizeAndValidate(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Classifier saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Classifier findEntity(UUID id) {
        Classifier entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(Classifier entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }
    protected boolean companyScoped() {
        return true;
    }
}
