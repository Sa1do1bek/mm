package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ClassifierCreateRequest;
import com.enterprise.mm.payload.ClassifierResponse;
import com.enterprise.mm.payload.ClassifierUpdateRequest;
import com.enterprise.mm.payload.ValuationMethodSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ClassifierMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Classifier;
import com.enterprise.mm.repository.ClassifierRepository;
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
public class ValuationMethodService {
    private static final String TYPE = "VALUATION_METHOD";
    private static final Set<String> SUPPORTED_CODES = Set.of(
        "ACTUAL_COST",
        "FIFO",
        "LIFO",
        "WEIGHTED_AVERAGE",
        "MOVING_AVERAGE",
        "MARKET_PRICE",
        "LOWER_OF_COST_OR_MARKET",
        "NET_REALIZABLE_VALUE"
    );
    private final ClassifierRepository repository;
    private final ClassifierMapper mapper;
    private final String entityName = "ValuationMethod";
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<ClassifierResponse> search(ValuationMethodSearchRequest request, Pageable pageable) {
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
            TYPE,
            pageable
        ).map(mapper::toResponse);
    }
    protected Classifier findEntity(UUID id) {
        Classifier entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!TYPE.equals(entity.getClassifierType())) {
            throw new NotFoundException("Valuation method not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(Classifier entity, ClassifierCreateRequest dto) {
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

        entity.setClassifierType(TYPE);
        validateSupportedCode(entity.getCode());
    }
    protected void beforeUpdate(Classifier entity, ClassifierUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setClassifierType(TYPE);
        validateSupportedCode(entity.getCode());
    }
    @Transactional
    public void softDelete(UUID id) {
        throw new BadRequestException("Valuation methods are a system dictionary and cannot be deleted");
    }
    private void validateSupportedCode(String code) {
        if (code == null || !SUPPORTED_CODES.contains(code.trim().toUpperCase(Locale.ROOT))) {
            throw new BadRequestException("Unsupported valuation method: " + code);
        }
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
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Classifier saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ClassifierResponse restore(UUID id) {
        Classifier entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        String oldSnapshot = auditService.snapshot(entity);
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        
        entity.restore();
        Classifier saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected boolean companyScoped() {
        return true;
    }
}

