package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.TnvedCodeResponse;
import com.enterprise.mm.payload.TnvedCodeUpdateRequest;
import com.enterprise.mm.payload.TnvedCodeCreateRequest;
import com.enterprise.mm.payload.TnvedCodeSearchRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TnvedCodeMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.repository.TnvedCodeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TnvedCodeService {
    private final TnvedCodeRepository repository;
    private final TnvedCodeMapper mapper;
    private final String entityName = "TnvedCode";
    private final AuditService auditService;
    private final EntityManager entityManager;
    protected void beforeCreate(TnvedCode entity, TnvedCodeCreateRequest dto) {
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

        normalize(entity);
        validateUniqueTnvedCode(entity);
    }
    protected void beforeUpdate(TnvedCode entity, TnvedCodeUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        validateUniqueTnvedCode(entity);
    }
    private void normalize(TnvedCode entity) {
        String tnvedCode = normalizeProductCode(entity.getTnvedCode());
        if (tnvedCode == null) {
            throw new BadRequestException("TN VED code is required");
        }
        entity.setTnvedCode(tnvedCode);
        entity.setCode(entity.getTnvedCode());
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getTnvedCode());
        }
        entity.setParentCode(normalizeProductCode(entity.getParentCode()));
        entity.setSectionCode(normalizeProductCode(entity.getSectionCode()));
        entity.setUnitCode(normalizeProductCode(entity.getUnitCode()));
        if (entity.getSourceSystem() != null) {
            entity.setSourceSystem(entity.getSourceSystem().trim().toUpperCase(Locale.ROOT));
        }
    }
    private void validateUniqueTnvedCode(TnvedCode entity) {
        String query = """
            select count(e)
            from TnvedCode e
            where e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.tnvedCode = :tnvedCode
              and e.isDeleted = false
            """;
        if (entity.getId() != null) {
            query += " and e.id <> :id";
        }
        var typedQuery = entityManager.createQuery(query, Long.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", entity.getCompanyId())
            .setParameter("tnvedCode", entity.getTnvedCode());
        if (entity.getId() != null) {
            typedQuery.setParameter("id", entity.getId());
        }
        if (typedQuery.getSingleResult() > 0) {
            throw new BadRequestException("TN VED code already exists in this company");
        }
    }
    private String normalizeProductCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
    @Transactional(readOnly = true)
    public Page<TnvedCodeResponse> search(TnvedCodeSearchRequest request, Pageable pageable) {
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
    public TnvedCodeResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public TnvedCodeResponse create(TnvedCodeCreateRequest dto) {
        TnvedCode entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        TnvedCode saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public TnvedCodeResponse update(UUID id, TnvedCodeUpdateRequest dto) {
        TnvedCode entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        TnvedCode saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        TnvedCode entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public TnvedCodeResponse restore(UUID id) {
        TnvedCode entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        TnvedCode saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected TnvedCode findEntity(UUID id) {
        TnvedCode entity = repository.findByIdAndIsDeletedFalse(id)
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

