package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.LabelCount;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.enums.SupplierRelationshipType;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierBankAccount;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierRepository repository;
    private final SupplierMapper mapper;
    private final String entityName = "Supplier";
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final EmployeeRepository employeeRepository;
    private final IdempotencyService idempotencyService;
    private static final int LIST_LIMIT = 5;


    protected void beforeCreate(Supplier entity, SupplierCreateRequest request) {
        entity.setStatus(SupplierStatus.NEW.name());
        entity.setCategory("C");
        entity.setRating(BigDecimal.ZERO);
        entity.setBlockedReason(null);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalizeSupplierTaxStructure(entity, request);
        normalizeWorkspaceProfile(entity, true);
        validateUniqueTaxId(entity, isBranchRequest(entity, request));
        normalizeAndValidateBankAccounts(entity, request);
    }
    protected void beforeUpdate(Supplier entity, SupplierUpdateRequest request) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalizeSupplierTaxStructure(entity, request);
        normalizeWorkspaceProfile(entity, false);
        validateUniqueTaxId(entity, isBranchRequest(entity, request));
        normalizeAndValidateBankAccounts(entity, request);
    }

    private void normalizeWorkspaceProfile(Supplier entity, boolean create) {
        String relationship = entity.getRelationshipType();
        if (relationship == null || relationship.isBlank()) {
            relationship = create ? SupplierRelationshipType.EXTERNAL.name() : entity.getRelationshipType();
        }
        if (relationship != null && !relationship.isBlank()) {
            entity.setRelationshipType(SupplierRelationshipType.valueOf(
                relationship.trim().toUpperCase(Locale.ROOT)).name());
        }
        entity.setCountryCode(upperOrNull(entity.getCountryCode()));
        validateOwnerEmployee(entity);
    }

    private void validateOwnerEmployee(Supplier entity) {
        if (entity.getOwnerEmployeeId() == null) {
            return;
        }
        if (employeeRepository == null) {
            return;
        }
        Employee employee = employeeRepository.findByIdAndIsDeletedFalse(entity.getOwnerEmployeeId())
            .orElseThrow(() -> new BadRequestException("Owner employee not found"));
        if (!TenantContext.currentTenant().equals(employee.getTenantId())
            || !Objects.equals(entity.getCompanyId(), employee.getCompanyId())) {
            throw new BadRequestException("Owner employee not found");
        }
    }

    private String upperOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateUniqueTaxId(Supplier entity, boolean branch) {
        if (entity.getTaxId() == null) {
            return;
        }
        boolean duplicate = repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getCompanyId())
            .stream()
            .filter(existing -> entity.getId() == null || !entity.getId().equals(existing.getId()))
            .map(Supplier::getTaxId)
            .map(this::normalizeTaxId)
            .anyMatch(candidate -> Objects.equals(candidate, entity.getTaxId()));
        if (duplicate) {
            throw new ConflictException(
                branch
                    ? "Supplier branch INN already exists in this company"
                    : "Supplier INN already exists in this company",
                "supplier.taxId.duplicate"
            );
        }
    }

    private void normalizeSupplierTaxStructure(Supplier entity, SupplierCreateRequest request) {
        boolean branch = isBranchRequest(entity, request);
        if (branch) {
            normalizeBranchTaxStructure(entity);
            return;
        }
        String taxId = normalizeTaxId(entity.getTaxId());
        entity.setTaxId(taxId);
        entity.setBaseTaxId(normalizeTaxId(entity.getBaseTaxId()) == null ? taxId : normalizeTaxId(entity.getBaseTaxId()));
        entity.setTaxIdSuffix(null);
        entity.setParentSupplierId(null);
        entity.setSupplierType("HEAD_OFFICE");
    }

    private boolean isBranchRequest(Supplier entity, SupplierCreateRequest request) {
        return Boolean.TRUE.equals(request.getCreateAsBranch())
            || "BRANCH".equalsIgnoreCase(Objects.toString(entity.getSupplierType(), ""))
            || entity.getParentSupplierId() != null
            || entity.getTaxIdSuffix() != null;
    }

    private void normalizeBranchTaxStructure(Supplier entity) {
        Supplier parent = null;
        if (entity.getParentSupplierId() != null) {
            parent = repository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getParentSupplierId())
                .filter(candidate -> Objects.equals(CompanyContext.currentCompany(), candidate.getCompanyId()))
                .orElseThrow(() -> new BadRequestException("Parent supplier not found"));
        }

        String baseTaxId = normalizeTaxId(entity.getBaseTaxId());
        if (baseTaxId == null && parent != null) {
            baseTaxId = normalizeTaxId(parent.getBaseTaxId());
            if (baseTaxId == null) {
                baseTaxId = normalizeTaxId(parent.getTaxId());
            }
        }
        if (baseTaxId == null) {
            baseTaxId = baseTaxIdFromTaxId(entity.getTaxId());
        }
        if (baseTaxId == null) {
            throw new BadRequestException("Supplier branch base INN is required");
        }

        if (parent == null) {
            parent = findHeadSupplier(entity, baseTaxId);
            entity.setParentSupplierId(parent == null ? null : parent.getId());
        }

        Integer suffix = entity.getTaxIdSuffix();
        if (suffix == null || suffix <= 0) {
            suffix = nextBranchSuffix(entity, baseTaxId);
        }
        entity.setBaseTaxId(baseTaxId);
        entity.setTaxIdSuffix(suffix);
        entity.setTaxId(baseTaxId + "_" + suffix);
        entity.setSupplierType("BRANCH");
        if ((entity.getBranchName() == null || entity.getBranchName().isBlank()) && entity.getShortName() != null) {
            entity.setBranchName(entity.getShortName());
        }
    }

    private Supplier findHeadSupplier(Supplier entity, String baseTaxId) {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getCompanyId())
            .stream()
            .filter(existing -> entity.getId() == null || !entity.getId().equals(existing.getId()))
            .filter(existing -> !"BRANCH".equalsIgnoreCase(Objects.toString(existing.getSupplierType(), "")))
            .filter(existing -> Objects.equals(normalizeTaxId(existing.getBaseTaxId()), baseTaxId)
                || Objects.equals(normalizeTaxId(existing.getTaxId()), baseTaxId))
            .findFirst()
            .orElse(null);
    }

    private Integer nextBranchSuffix(Supplier entity, String baseTaxId) {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getCompanyId())
            .stream()
            .filter(existing -> entity.getId() == null || !entity.getId().equals(existing.getId()))
            .filter(existing -> Objects.equals(normalizeTaxId(existing.getBaseTaxId()), baseTaxId)
                || Objects.equals(baseTaxIdFromTaxId(existing.getTaxId()), baseTaxId))
            .map(Supplier::getTaxIdSuffix)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .map(value -> value + 1)
            .orElse(1);
    }

    private String baseTaxIdFromTaxId(String value) {
        String taxId = normalizeTaxId(value);
        if (taxId == null) {
            return null;
        }
        return taxId.replaceFirst("_[0-9]+$", "");
    }

    private String normalizeTaxId(String value) {
        return Supplier.normalizeBankAccountValue(value);
    }
    private void normalizeAndValidateBankAccounts(Supplier entity, SupplierCreateRequest request) {
        syncBankAccounts(entity, request);
        entity.normalizeBankAccountForUniqueness();
        Set<String> hashes = new HashSet<>();
        for (SupplierBankAccount account : entity.getBankAccounts()) {
            account.normalizeBankAccountForUniqueness();
            if (account.getBankAccountHash() == null) {
                continue;
            }
            if (!hashes.add(account.getBankAccountHash())) {
                throw new ConflictException("Supplier bank account already exists in this company", "supplier.bankAccount.duplicate");
            }
            if (hasBankAccountDuplicate(entity, account.getBankAccountHash()) || hasLegacyBankAccountDuplicate(entity, account.getBankAccount())) {
                throw new ConflictException("Supplier bank account already exists in this company", "supplier.bankAccount.duplicate");
            }
        }
    }

    private void syncBankAccounts(Supplier entity, SupplierCreateRequest request) {
        if (request.getBankAccounts() == null && hasFlatBankAccountDetails(entity)) {
            SupplierBankAccount primary = selectPrimaryBankAccount(entity.getBankAccounts());
            if (primary == null) {
                entity.getBankAccounts().add(bankAccountFromFlatFields(entity));
            } else {
                applyFlatFieldsToBankAccount(entity, primary);
            }
        }

        List<SupplierBankAccount> usableAccounts = new ArrayList<>();
        for (SupplierBankAccount account : entity.getBankAccounts()) {
            if (!hasBankAccountDetails(account)) {
                continue;
            }
            account.setSupplier(entity);
            account.setCompanyId(entity.getCompanyId());
            account.setTenantId(TenantContext.currentTenant());
            usableAccounts.add(account);
        }
        entity.getBankAccounts().clear();
        entity.getBankAccounts().addAll(usableAccounts);

        SupplierBankAccount primary = selectPrimaryBankAccount(entity.getBankAccounts());
        for (SupplierBankAccount account : entity.getBankAccounts()) {
            account.setPrimaryAccount(account == primary);
        }
        applyPrimaryBankAccountToFlatFields(entity, primary);
    }

    private SupplierBankAccount bankAccountFromFlatFields(Supplier entity) {
        SupplierBankAccount account = new SupplierBankAccount();
        account.setSupplier(entity);
        applyFlatFieldsToBankAccount(entity, account);
        account.setPrimaryAccount(true);
        return account;
    }

    private void applyFlatFieldsToBankAccount(Supplier entity, SupplierBankAccount account) {
        account.setBankName(entity.getBankName());
        account.setBankMfo(entity.getBankMfo());
        account.setBankAccount(entity.getBankAccount());
        account.setBankAddress(entity.getBankAddress());
    }

    private SupplierBankAccount selectPrimaryBankAccount(List<SupplierBankAccount> accounts) {
        return accounts.stream()
            .filter(SupplierBankAccount::isPrimaryAccount)
            .findFirst()
            .orElseGet(() -> accounts.isEmpty() ? null : accounts.get(0));
    }

    private void applyPrimaryBankAccountToFlatFields(Supplier entity, SupplierBankAccount primary) {
        if (primary == null) {
            entity.setBankName(null);
            entity.setBankMfo(null);
            entity.setBankAccount(null);
            entity.setBankAddress(null);
            entity.setBankAccountHash(null);
            return;
        }
        primary.normalizeBankAccountForUniqueness();
        entity.setBankName(primary.getBankName());
        entity.setBankMfo(primary.getBankMfo());
        entity.setBankAccount(primary.getBankAccount());
        entity.setBankAddress(primary.getBankAddress());
        entity.setBankAccountHash(primary.getBankAccountHash());
    }

    private boolean hasFlatBankAccountDetails(Supplier entity) {
        return hasText(entity.getBankName())
            || hasText(entity.getBankMfo())
            || hasText(entity.getBankAccount())
            || hasText(entity.getBankAddress());
    }

    private boolean hasBankAccountDetails(SupplierBankAccount account) {
        return hasText(account.getBankName())
            || hasText(account.getBankMfo())
            || hasText(account.getBankAccount())
            || hasText(account.getBankAddress());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasBankAccountDuplicate(Supplier entity, String bankAccountHash) {
        String query = """
            select count(e)
            from SupplierBankAccount e
            where e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.bankAccountHash = :bankAccountHash
              and e.isDeleted = false
            """;
        if (entity.getId() != null) {
            query += " and e.supplier.id <> :supplierId";
        }
        var typedQuery = entityManager.createQuery(query, Long.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", entity.getCompanyId())
            .setParameter("bankAccountHash", bankAccountHash);
        if (entity.getId() != null) {
            typedQuery.setParameter("supplierId", entity.getId());
        }
        return typedQuery.getSingleResult() > 0;
    }
    private boolean hasLegacyBankAccountDuplicate(Supplier entity, String bankAccount) {
        String normalizedBankAccount = Supplier.normalizeBankAccountValue(bankAccount);
        if (normalizedBankAccount == null) {
            return false;
        }
        String query = """
            select e
            from Supplier e
            where e.tenantId = :tenantId
              and e.companyId = :companyId
              and e.isDeleted = false
            """;
        if (entity.getId() != null) {
            query += " and e.id <> :id";
        }
        var typedQuery = entityManager.createQuery(query, Supplier.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", entity.getCompanyId());
        if (entity.getId() != null) {
            typedQuery.setParameter("id", entity.getId());
        }
        return typedQuery.getResultStream()
            .map(Supplier::getBankAccount)
            .map(Supplier::normalizeBankAccountValue)
            .anyMatch(candidate -> Objects.equals(candidate, normalizedBankAccount));
    }
    @Transactional(readOnly = true)
    public Page<SupplierResponse> search(SupplierSearchRequest request, Pageable pageable) {
        SupplierSearchRequest supplierRequest = request == null ? SupplierSearchRequest.empty() : request;
        String taxId = normalizeFilter(supplierRequest.taxId());
        String bankMfo = normalizeFilter(supplierRequest.bankMfo());
        String bankAccount = normalizeFilter(supplierRequest.bankAccount());
        if (taxId != null || bankMfo != null || bankAccount != null) {
            return filterSensitiveSearch(supplierRequest, pageable, taxId, bankMfo, bankAccount).map(mapper::toResponse);
        }
        return repository.findAll(supplierSearchSpec(supplierRequest, false), pageable).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public Page<SupplierResponse> searchBlacklist(String category, Pageable pageable) {
        SupplierSearchRequest request = new SupplierSearchRequest(
            null,
            List.of(SupplierStatus.BLOCKED.name()),
            null,
            null,
            false,
            category,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        return repository.findAll(supplierSearchSpec(request, false), pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public SupplierResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierResponse create(SupplierCreateRequest request) {
        return create(request, null);
    }

    @Transactional
    public SupplierResponse create(SupplierCreateRequest request, String idempotencyKey) {
        return idempotencyService.execute(
            "CREATE_SUPPLIER",
            null,
            idempotencyKey,
            request,
            SupplierResponse.class,
            () -> doCreate(request)
        );
    }

    private SupplierResponse doCreate(SupplierCreateRequest request) {
        Supplier entity = mapper.toEntity(request);
        beforeCreate(entity, request);
        Supplier saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    /** Called only by the ADMIN-only source-bound migration service; normal create validation is unchanged. */
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public SupplierResponse createLegacyMigration(com.enterprise.mm.payload.LegacySupplierMigrationPayload payload) {
        long confirmedAccounts = payload.bankAccounts() == null ? 0 : payload.bankAccounts().stream()
            .filter(account -> Boolean.TRUE.equals(account.getPrimaryAccount())).count();
        if (payload.primaryBankAccountConfirmed() && confirmedAccounts != 1) {
            throw new BadRequestException("Legacy primary bank account requires exactly one confirmed source account");
        }
        SupplierCreateRequest request = payload.toCreateRequest();
        Supplier entity = mapper.toEntity(request);
        beforeCreate(entity, request);
        // Canonical localized names bypass the workflow-word heuristic in the entity callback.
        // Real supplier names such as "Transit Line Ltd." must retain their source spelling.
        entity.setName(com.enterprise.mm.common.LocalizedText.ensureComplete(entity.getName()));
        if (!payload.primaryBankAccountConfirmed()) {
            entity.getBankAccounts().forEach(account -> account.setPrimaryAccount(false));
            applyPrimaryBankAccountToFlatFields(entity, null);
        }
        if (payload.markedForDeletion()) entity.setStatus(SupplierStatus.ARCHIVED.name());
        Supplier saved = repository.save(entity);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "LEGACY_IMPORT", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierResponse update(UUID id, SupplierUpdateRequest request) {
        Supplier entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        if (request == null || !Objects.equals(entity.getVersion(), request.getExpectedVersion())) {
            throw new ConflictException(
                "Supplier master was changed by another user; refresh the card and retry");
        }
        String status = entity.getStatus();
        String category = entity.getCategory();
        BigDecimal rating = entity.getRating();
        String blockedReason = entity.getBlockedReason();
        mapper.updateEntity(entity, request);
        entity.setStatus(status);
        entity.setCategory(category);
        entity.setRating(rating);
        entity.setBlockedReason(blockedReason);
        beforeUpdate(entity, request);
        Supplier saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Supplier entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierResponse restore(UUID id) {
        Supplier entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId()) || !entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        SupplierCreateRequest restoreRequest = new SupplierCreateRequest();
        normalizeSupplierTaxStructure(entity, restoreRequest);
        normalizeWorkspaceProfile(entity, false);
        validateUniqueTaxId(entity, "BRANCH".equalsIgnoreCase(Objects.toString(entity.getSupplierType(), "")));
        normalizeAndValidateBankAccounts(entity, restoreRequest);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Supplier saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Supplier findEntity(UUID id) {
        Supplier entity = repository.findByIdAndIsDeletedFalse(id)
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

    private Supplier findMutableEntity(UUID id) {
        Supplier entity = findEntity(id);
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private Specification<Supplier> supplierSearchSpec(SupplierSearchRequest request, boolean includeSensitiveFilters) {
        SupplierSearchRequest supplierRequest = request == null ? SupplierSearchRequest.empty() : request;
        SearchCriteria filter = SearchCriteria.of(supplierRequest);
        UUID companyId = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
            ? CompanyContext.currentCompany()
            : null;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            if (companyId != null) {
                predicates.add(cb.equal(root.get("companyId"), companyId));
            }
            if (!filter.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }

            String search = normalizeFilter(filter.search());
            if (search != null) {
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), contains(search)),
                    cb.like(cb.lower(root.get("name")), contains(search))
                ));
            }
            if (filter.fromDateTime() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.fromDateTime()));
            }
            if (filter.toDateTime() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.toDateTime()));
            }
            if (filter.hasStatus()) {
                predicates.add(root.get("status").in(filter.status()));
            }

            addExactText(predicates, cb, cb.lower(root.get("category")), supplierRequest.category());
            addGreaterThanOrEqualTo(predicates, cb, root.get("rating"), supplierRequest.ratingMin());
            addLessThanOrEqualTo(predicates, cb, root.get("rating"), supplierRequest.ratingMax());
            addContains(predicates, cb, cb.lower(root.get("legalName")), supplierRequest.legalName());
            addContains(predicates, cb, cb.lower(root.get("shortName")), supplierRequest.shortName());
            addContains(predicates, cb, cb.lower(root.get("okedCode")), supplierRequest.okedCode());
            addContains(predicates, cb, cb.lower(root.get("vatRegistrationCode")), supplierRequest.vatRegistrationCode());
            addContains(predicates, cb, cb.lower(root.get("registrationNumber")), supplierRequest.registrationNumber());
            addGreaterThanOrEqualTo(predicates, cb, root.get("registrationDate"), supplierRequest.registrationDateFrom());
            addLessThanOrEqualTo(predicates, cb, root.get("registrationDate"), supplierRequest.registrationDateTo());
            addContains(predicates, cb, cb.lower(root.get("legalAddress")), supplierRequest.legalAddress());
            addContains(predicates, cb, cb.lower(root.get("actualAddress")), supplierRequest.actualAddress());
            addContains(predicates, cb, cb.lower(root.get("phone")), supplierRequest.phone());
            addContains(predicates, cb, cb.lower(root.get("email")), supplierRequest.email());
            addContains(predicates, cb, cb.lower(root.get("directorName")), supplierRequest.directorName());
            addContains(predicates, cb, cb.lower(root.get("chiefAccountantName")), supplierRequest.chiefAccountantName());
            addContains(predicates, cb, cb.lower(root.get("bankName")), supplierRequest.bankName());
            addContains(predicates, cb, cb.lower(root.get("bankAddress")), supplierRequest.bankAddress());
            addContains(predicates, cb, cb.lower(root.get("blockedReason")), supplierRequest.blockedReason());
            if (includeSensitiveFilters) {
                addContains(predicates, cb, cb.lower(root.get("taxId")), supplierRequest.taxId());
                addContains(predicates, cb, cb.lower(root.get("bankMfo")), supplierRequest.bankMfo());
                addContains(predicates, cb, cb.lower(root.get("bankAccount")), supplierRequest.bankAccount());
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Page<Supplier> filterSensitiveSearch(
        SupplierSearchRequest request,
        Pageable pageable,
        String taxId,
        String bankMfo,
        String bankAccount
    ) {
        int batchSize = Math.max(pageable.getPageSize(), 100);
        int pageNumber = 0;
        long offset = pageable.getOffset();
        List<Supplier> matches = new ArrayList<>();
        long totalMatches = 0;
        Page<Supplier> batch;
        do {
            batch = repository.findAll(
                supplierSearchSpec(request, false),
                PageRequest.of(pageNumber++, batchSize, pageable.getSort())
            );
            for (Supplier supplier : batch.getContent()) {
                if (!matchesSensitiveFilters(supplier, taxId, bankMfo, bankAccount)) {
                    continue;
                }
                if (totalMatches >= offset && matches.size() < pageable.getPageSize()) {
                    matches.add(supplier);
                }
                totalMatches++;
            }
        } while (batch.hasNext());
        return new PageImpl<>(matches, pageable, totalMatches);
    }

    private boolean matchesSensitiveFilters(Supplier supplier, String taxId, String bankMfo, String bankAccount) {
        return containsIgnoreCase(supplier.getTaxId(), taxId)
            && containsIgnoreCase(supplier.getBankMfo(), bankMfo)
            && containsIgnoreCase(supplier.getBankAccount(), bankAccount);
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String contains(String value) {
        return "%" + value + "%";
    }

    private boolean containsIgnoreCase(String value, String search) {
        if (search == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(search);
    }

    private void addExactText(List<Predicate> predicates, CriteriaBuilder cb, Expression<String> path, String value) {
        String normalized = normalizeFilter(value);
        if (normalized != null) {
            predicates.add(cb.equal(path, normalized));
        }
    }

    private void addContains(
        List<Predicate> predicates,
        CriteriaBuilder cb,
        Expression<String> path,
        String value
    ) {
        String normalized = normalizeFilter(value);
        if (normalized != null) {
            predicates.add(cb.like(path, contains(normalized)));
        }
    }

    private <T extends Comparable<? super T>> void addGreaterThanOrEqualTo(
        List<Predicate> predicates,
        CriteriaBuilder cb,
        Expression<T> path,
        T value
    ) {
        if (value != null) {
            predicates.add(cb.greaterThanOrEqualTo(path, value));
        }
    }

    private <T extends Comparable<? super T>> void addLessThanOrEqualTo(
        List<Predicate> predicates,
        CriteriaBuilder cb,
        Expression<T> path,
        T value
    ) {
        if (value != null) {
            predicates.add(cb.lessThanOrEqualTo(path, value));
        }
    }

    @Transactional(readOnly = true)
    public SupplierDashboardResponse buildDashboard() {
        UUID companyFilter =  isSuperAdmin() ? null : CompanyContext.currentCompany();

        SupplierSummary summary = buildSummary(companyFilter);

        List<LabelCountDto> byCategory = toLabelCounts(repository.countGroupedByCategory(companyFilter));
        List<LabelCountDto> byStatus = toLabelCounts(repository.countGroupedByStatus(companyFilter));
        List<LabelCountDto> byRelationshipType =
                toLabelCounts(repository.countGroupedByRelationshipType(companyFilter));
        List<LabelCountDto> bySupplierType = toLabelCounts(repository.countGroupedBySupplierType(companyFilter));
        List<LabelCountDto> byCountry = toLabelCounts(repository.countGroupedByCountry(companyFilter));

        BigDecimal averageRating = Optional.ofNullable(repository.averageRating(companyFilter))
                .orElse(BigDecimal.ZERO);

        List<SupplierShortDto> topRated = repository
                .findTopRated(companyFilter, PageRequest.of(0, LIST_LIMIT))
                .stream().map(this::toShortDto).toList();

        List<SupplierShortDto> recentlyAdded = repository
                .findRecent(companyFilter, PageRequest.of(0, LIST_LIMIT))
                .stream().map(this::toShortDto).toList();

        List<SupplierAlertDto> openFirst = buildOpenFirst(summary);

        return new SupplierDashboardResponse(
                summary,
                byCategory,
                byStatus,
                byRelationshipType,
                bySupplierType,
                byCountry,
                averageRating,
                topRated,
                recentlyAdded,
                openFirst
        );
    }
    private SupplierSummary buildSummary(UUID companyFilter) {
        long total = companyFilter == null
                ? repository.countByIsDeletedFalse()
                : repository.countByCompanyIdAndIsDeletedFalse(companyFilter);

        long blocked = companyFilter == null
                ? repository.countByBlockedReasonIsNotNullAndIsDeletedFalse()
                : repository.countByCompanyIdAndBlockedReasonIsNotNullAndIsDeletedFalse(companyFilter);

        long withoutBank = companyFilter == null
                ? repository.countByBankAccountIsNullAndIsDeletedFalse()
                : repository.countByCompanyIdAndBankAccountIsNullAndIsDeletedFalse(companyFilter);

        long withoutTaxId = companyFilter == null
                ? repository.countByTaxIdIsNullAndIsDeletedFalse()
                : repository.countByCompanyIdAndTaxIdIsNullAndIsDeletedFalse(companyFilter);

        long headOffice = companyFilter == null
                ? repository.countByParentSupplierIdIsNullAndIsDeletedFalse()
                : repository.countByCompanyIdAndParentSupplierIdIsNullAndIsDeletedFalse(companyFilter);

        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long newThisMonth = companyFilter == null
                ? repository.countByCreatedAtAfterAndIsDeletedFalse(monthStart)
                : repository.countByCompanyIdAndCreatedAtAfterAndIsDeletedFalse(companyFilter, monthStart);

        long branchSuppliers = total - headOffice;

        return new SupplierSummary(total, blocked, withoutBank, withoutTaxId, newThisMonth, headOffice,
                branchSuppliers);
    }

    /** Same idea as the screenshot's "Open first" list: sorted by how many suppliers need attention. */
    private List<SupplierAlertDto> buildOpenFirst(SupplierSummary summary) {
        List<SupplierAlertDto> alerts = new ArrayList<>();
        if (summary.blocked() > 0) {
            alerts.add(new SupplierAlertDto(
                    "BLOCKED",
                    "Bloklangan yetkazib beruvchilar",
                    "Blocker (blockedReason) hal qilinishi kerak",
                    summary.blocked()));
        }
        if (summary.withoutBankAccount() > 0) {
            alerts.add(new SupplierAlertDto(
                    "NO_BANK_ACCOUNT",
                    "Bank hisobi kiritilmagan",
                    "To'lov qilish uchun bank rekvizitlari yo'q",
                    summary.withoutBankAccount()));
        }
        if (summary.withoutTaxId() > 0) {
            alerts.add(new SupplierAlertDto(
                    "NO_TAX_ID",
                    "STIR (taxId) kiritilmagan",
                    "Soliq raqami to'ldirilmagan",
                    summary.withoutTaxId()));
        }
        alerts.sort(Comparator.comparingLong(SupplierAlertDto::count).reversed());
        return alerts;
    }

    private SupplierShortDto toShortDto(Supplier supplier) {
        return new SupplierShortDto(
                supplier.getId(),
                supplier.getLegalName(),
                supplier.getShortName(),
                supplier.getCategory(),
                supplier.getRating(),
                supplier.getCountryCode(),
                supplier.getStatus(),
                supplier.getBlockedReason()
        );
    }

    private List<LabelCountDto> toLabelCounts(List<LabelCount> raw) {
        return raw.stream()
                .map(r -> new LabelCountDto(r.getLabel() == null ? "N/A" : r.getLabel(), r.getTotal()))
                .toList();
    }
    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> RoleName.ADMIN.equals(a.getAuthority()));
    }
}
