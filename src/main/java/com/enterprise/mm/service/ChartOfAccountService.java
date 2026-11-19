package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ChartOfAccountResponse;
import com.enterprise.mm.payload.ChartOfAccountUpdateRequest;
import com.enterprise.mm.payload.ChartOfAccountCreateRequest;
import com.enterprise.mm.payload.ChartOfAccountSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ChartOfAccountMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ChartOfAccount;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
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
public class ChartOfAccountService {
    private final ChartOfAccountRepository repository;
    private final ChartOfAccountMapper mapper;
    private final String entityName = "ChartOfAccount";
    private final AuditService auditService;
    private final EntityManager entityManager;

    protected void beforeCreate(ChartOfAccount entity, ChartOfAccountCreateRequest dto) {
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
        validateHierarchy(entity);
        validateUniqueAccountCode(entity);
    }
    protected void beforeUpdate(ChartOfAccount entity, ChartOfAccountUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        validateHierarchy(entity);
        validateUniqueAccountCode(entity);
    }
    private void normalize(ChartOfAccount entity) {
        if (entity.getAccountCode() == null || entity.getAccountCode().isBlank()) {
            throw new BadRequestException("Account code is required");
        }
        entity.setAccountCode(entity.getAccountCode().trim().toUpperCase(Locale.ROOT));
        entity.setCode(entity.getAccountCode());
        if (entity.getParentCode() != null) {
            String parent = entity.getParentCode().trim();
            entity.setParentCode(parent.isEmpty() ? null : parent.toUpperCase(Locale.ROOT));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getAccountCode());
        }
        if (entity.getAccountType() != null) {
            entity.setAccountType(entity.getAccountType().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getSourceSystem() != null) {
            entity.setSourceSystem(entity.getSourceSystem().trim().toUpperCase(Locale.ROOT));
        }
    }
    private void validateUniqueAccountCode(ChartOfAccount entity) {
        String query = """
            select count(e)
            from ChartOfAccount e
            where e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.accountCode = :accountCode
              and e.isDeleted = false
            """;
        if (entity.getId() != null) {
            query += " and e.id <> :id";
        }
        var typedQuery = entityManager.createQuery(query, Long.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", entity.getCompanyId())
            .setParameter("accountCode", entity.getAccountCode());
        if (entity.getId() != null) {
            typedQuery.setParameter("id", entity.getId());
        }
        if (typedQuery.getSingleResult() > 0) {
            throw new BadRequestException("Chart of account code already exists in this company");
        }
    }

    private void validateHierarchy(ChartOfAccount entity) {
        if (entity.getParentCode() == null) {
            return;
        }
        Set<String> visited = new HashSet<>();
        visited.add(entity.getAccountCode());
        String cursorCode = entity.getParentCode();
        while (cursorCode != null) {
            if (!visited.add(cursorCode)) {
                throw new BadRequestException("Chart of accounts hierarchy cycle detected");
            }
            String lookupCode = cursorCode;
            ChartOfAccount parent = repository
                .findByTenantIdAndCompanyIdAndAccountCodeAndIsDeletedFalse(
                    TenantContext.currentTenant(), entity.getCompanyId(), lookupCode)
                .orElseThrow(() -> new BadRequestException(
                    "Parent chart of account not found in the current company: " + lookupCode));
            cursorCode = parent.getParentCode() == null || parent.getParentCode().isBlank()
                ? null
                : parent.getParentCode().trim().toUpperCase(Locale.ROOT);
        }
    }
    @Transactional(readOnly = true)
    public Page<ChartOfAccountResponse> search(ChartOfAccountSearchRequest request, Pageable pageable) {
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
    public ChartOfAccountResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ChartOfAccountResponse create(ChartOfAccountCreateRequest dto) {
        ChartOfAccount entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ChartOfAccount saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ChartOfAccountResponse update(UUID id, ChartOfAccountUpdateRequest dto) {
        ChartOfAccount entity = findEntity(id);
        String previousAccountCode = entity.getAccountCode();
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        if (!Objects.equals(previousAccountCode, entity.getAccountCode())) {
            requireNoImportedWarehouseEntries(entity, previousAccountCode);
        }
        ChartOfAccount saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ChartOfAccount entity = findEntity(id);
        requireNoImportedWarehouseEntries(entity, entity.getAccountCode());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ChartOfAccountResponse restore(UUID id) {
        ChartOfAccount entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        normalize(entity);
        validateHierarchy(entity);
        validateUniqueAccountCode(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ChartOfAccount saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ChartOfAccount findEntity(UUID id) {
        ChartOfAccount entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void requireNoImportedWarehouseEntries(ChartOfAccount account, String code) {
        if (code == null || code.isBlank()) return;
        var matches = entityManager.createNativeQuery("""
            select 1 from warehouse_account_entries e
            join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED'
            where e.tenant_id=:tenantId and e.company_id=:companyId and e.account_code=:accountCode
            limit 1
            """)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", account.getCompanyId())
            .setParameter("accountCode", code)
            .getResultList();
        if (!matches.isEmpty()) {
            throw new BadRequestException("Account code is used by imported warehouse balances; keep the code and edit its name or status instead");
        }
    }

    private void validateScope(ChartOfAccount entity) {
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
