package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.IkpuCodeResponse;
import com.enterprise.mm.payload.IkpuCodeUpdateRequest;
import com.enterprise.mm.payload.IkpuCodeCreateRequest;
import com.enterprise.mm.payload.IkpuCodeSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.IkpuCodeMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IkpuCode;
import com.enterprise.mm.repository.IkpuCodeRepository;
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
public class IkpuCodeService {
    private final IkpuCodeRepository repository;
    private final IkpuCodeMapper mapper;
    private final String entityName = "IkpuCode";
    private final AuditService auditService;
    private final EntityManager entityManager;
    protected void beforeCreate(IkpuCode entity, IkpuCodeCreateRequest dto) {
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
        validateUniqueIkpuCode(entity);
    }
    protected void beforeUpdate(IkpuCode entity, IkpuCodeUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        validateUniqueIkpuCode(entity);
    }
    private void normalize(IkpuCode entity) {
        String ikpuCode = normalizeProductCode(entity.getIkpuCode());
        if (ikpuCode == null) {
            throw new BadRequestException("IKPU code is required");
        }
        entity.setIkpuCode(ikpuCode);
        entity.setCode(entity.getIkpuCode());
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getIkpuCode());
        }
        entity.setPackageCode(normalizeProductCode(entity.getPackageCode()));
        entity.setUnitCode(normalizeProductCode(entity.getUnitCode()));
        entity.setGroupCode(normalizeProductCode(entity.getGroupCode()));
        if (entity.getSourceSystem() != null) {
            entity.setSourceSystem(entity.getSourceSystem().trim().toUpperCase(Locale.ROOT));
        }
    }
    private void validateUniqueIkpuCode(IkpuCode entity) {
        String query = """
            select count(e)
            from IkpuCode e
            where e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.ikpuCode = :ikpuCode
              and e.isDeleted = false
            """;
        if (entity.getId() != null) {
            query += " and e.id <> :id";
        }
        var typedQuery = entityManager.createQuery(query, Long.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", entity.getCompanyId())
            .setParameter("ikpuCode", entity.getIkpuCode());
        if (entity.getId() != null) {
            typedQuery.setParameter("id", entity.getId());
        }
        if (typedQuery.getSingleResult() > 0) {
            throw new BadRequestException("IKPU code already exists in this company");
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
    public Page<IkpuCodeResponse> search(IkpuCodeSearchRequest request, Pageable pageable) {
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
    public IkpuCodeResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public IkpuCodeResponse create(IkpuCodeCreateRequest dto) {
        IkpuCode entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        IkpuCode saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public IkpuCodeResponse update(UUID id, IkpuCodeUpdateRequest dto) {
        IkpuCode entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        IkpuCode saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        IkpuCode entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public IkpuCodeResponse restore(UUID id) {
        IkpuCode entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        IkpuCode saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected IkpuCode findEntity(UUID id) {
        IkpuCode entity = repository.findByIdAndIsDeletedFalse(id)
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

