package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalActionRequest;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalAccountUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierPortalAccountMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierPortalAccountService {
    private final SupplierPortalAccountRepository repository;
    private final SupplierPortalAccountMapper mapper;
    private final String entityName = "SupplierPortalAccount";
    private final AuditService auditService;
    private final SupplierComplianceService complianceService;
    private final SupplierMagicLinkService magicLinkService;
    protected void beforeCreate(SupplierPortalAccount entity, SupplierPortalAccountCreateRequest dto) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        requireCurrentCompanySupplier(supplier);
        entity.setCompanyId(CompanyContext.currentCompany());
        applySupplierEmail(entity, supplier);
        normalize(entity);
        normalizeRole(entity);
        validateUniqueEmail(entity);
        LocalDateTime invitedAt = LocalDateTime.now();
        entity.setInvitedAt(invitedAt);
        entity.setInviteTokenHash(null);
        entity.setInviteTokenExpiresAt(null);
        entity.setActivatedAt(invitedAt);
        entity.setLastLoginAt(null);
        entity.setLastSubmissionAt(null);
        entity.setTermsAcceptedAt(null);
        entity.setPortalTokenHash(null);
        entity.setPortalTokenExpiresAt(null);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus(SupplierPortalStatus.ACTIVE.name());
    }
    protected void beforeUpdate(SupplierPortalAccount entity, SupplierPortalAccountUpdateRequest dto) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        requireCurrentCompanySupplier(supplier);
        applySupplierEmail(entity, supplier);
        normalize(entity);
        normalizeRole(entity);
        validateUniqueEmail(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    @Deprecated(forRemoval = false)
    public SupplierPortalAccountResponse activate(UUID id, SupplierPortalActionRequest request) {
        findEntity(id);
        throw new BusinessException(
            "Administrative activation is disabled; supplier must use the public invitation exchange"
        );
    }
    @Transactional
    public SupplierPortalAccountResponse suspend(UUID id, SupplierPortalActionRequest request) {
        SupplierPortalAccount account = findEntity(id);
        requireMeaningfulComment(request == null ? null : request.comment(), "comment");
        if (!SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())) {
            throw new BusinessException("Only ACTIVE portal account can be suspended");
        }
        ensureNotLastActiveSupplierAdmin(account, "suspend");
        account.setStatus(SupplierPortalStatus.SUSPENDED.name());
        account.setPortalTokenHash(null);
        account.setPortalTokenExpiresAt(null);
        auditService.log("SupplierPortalAccount", id, "SUSPEND", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.saveAndFlush(account));
    }
    @Transactional
    public SupplierPortalAccountResponse revoke(UUID id, SupplierPortalActionRequest request) {
        SupplierPortalAccount account = findEntity(id);
        requireMeaningfulComment(request == null ? null : request.comment(), "comment");
        if (!SupplierPortalStatus.INVITED.name().equals(account.getStatus())
            && !SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())
            && !SupplierPortalStatus.SUSPENDED.name().equals(account.getStatus())) {
            throw new BusinessException("Only INVITED, ACTIVE or SUSPENDED portal account can be revoked");
        }
        ensureNotLastActiveSupplierAdmin(account, "revoke");
        account.setStatus(SupplierPortalStatus.REVOKED.name());
        account.setInviteTokenHash(null);
        account.setInviteTokenExpiresAt(null);
        account.setPortalTokenHash(null);
        account.setPortalTokenExpiresAt(null);
        auditService.log("SupplierPortalAccount", id, "REVOKE", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.saveAndFlush(account));
    }

    @Transactional
    public SupplierPortalAccountResponse reinvite(UUID id, Long expectedVersion, String comment) {
        SupplierPortalAccount account = findEntity(id);
        requireVersion(account, expectedVersion);
        requireMeaningfulComment(comment, "comment");
        if (!SupplierPortalStatus.INVITED.name().equals(account.getStatus())
            && !SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())) {
            throw new BusinessException("Only INVITED or ACTIVE portal account can be re-invited");
        }

        magicLinkService.queueLatestForAccount(account, "ADMIN_REINVITE: " + comment.trim());
        LocalDateTime invitedAt = LocalDateTime.now();
        account.setStatus(SupplierPortalStatus.INVITED.name());
        account.setInvitedAt(invitedAt);
        account.setInviteTokenHash(null);
        account.setInviteTokenExpiresAt(null);
        account.setActivatedAt(null);
        account.setPortalTokenHash(null);
        account.setPortalTokenExpiresAt(null);

        SupplierPortalAccount saved = repository.saveAndFlush(account);
        auditService.log("SupplierPortalAccount", id, "REINVITE", comment.trim(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    @Deprecated(forRemoval = false)
    public SupplierPortalAccountResponse acceptTerms(UUID id) {
        findEntity(id);
        throw new BusinessException(
            "Administrative terms acceptance is disabled; supplier must accept terms through the public session"
        );
    }
    private void normalize(SupplierPortalAccount entity) {
        if (entity.getLoginEmail() == null || entity.getLoginEmail().isBlank()) {
            throw new BusinessException("loginEmail is required");
        }
        entity.setLoginEmail(entity.getLoginEmail().trim().toLowerCase(Locale.ROOT));
    }

    private void normalizeRole(SupplierPortalAccount entity) {
        String value = entity.getPortalRole() == null || entity.getPortalRole().isBlank()
            ? SupplierPortalRole.SUPPLIER_ADMIN.name()
            : entity.getPortalRole().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            entity.setPortalRole(SupplierPortalRole.valueOf(value).name());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported supplier portal role: " + entity.getPortalRole());
        }
    }
    private void validateUniqueEmail(SupplierPortalAccount entity) {
        repository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getLoginEmail()
            )
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new ConflictException("Supplier portal login email already exists", "supplierPortal.loginEmail.duplicate");
            });
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public SupplierPortalAccount ensureForRfqInvitation(UUID supplierId, String recipientEmail) {
        Supplier supplier = complianceService.validateSupplier(supplierId);
        requireCurrentCompanySupplier(supplier);
        String normalizedEmail = supplierPortalEmail(supplier);
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new BusinessException("Supplier email is required for supplier portal account");
        }
        List<SupplierPortalAccount> existingAccounts = repository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                supplierId
            );
        var existing = existingAccounts.stream()
            .filter(account -> normalizedEmail.equalsIgnoreCase(account.getLoginEmail()))
            .findFirst();
        if (existing.isPresent()) {
            SupplierPortalAccount account = existing.get();
            if (!SupplierPortalStatus.INVITED.name().equals(account.getStatus())
                && !SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())) {
                throw new BusinessException("Supplier portal account conflicts with the RFQ recipient email");
            }
            if (account.getLoginEmail() == null || !account.getLoginEmail().equalsIgnoreCase(normalizedEmail)) {
                String oldSnapshot = auditService.snapshot(account);
                account.setLoginEmail(normalizedEmail);
                validateUniqueEmail(account);
                account = repository.save(account);
                auditService.log("SupplierPortalAccount", account.getId(), "SYNC_SUPPLIER_EMAIL",
                    supplierId.toString(), oldSnapshot, account);
            }
            return account;
        }
        repository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizedEmail
        ).ifPresent(account -> {
            throw new BusinessException("Supplier portal login email already belongs to another supplier");
        });

        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(supplierId);
        account.setLoginEmail(normalizedEmail);
        account.setContactPerson(supplier.getDirectorName());
        account.setPortalRole(SupplierPortalRole.SUPPLIER_ADMIN.name());
        account.setPhone(supplier.getPhone());
        account.setCode("SUPPLIER_PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        account.setName(BusinessDisplayNamePolicy.requireHumanName(
            "SupplierPortalAccount",
            supplier.getName(),
            account.getCode()
        ));
        account.setStatus(SupplierPortalStatus.INVITED.name());
        account.setInvitedAt(LocalDateTime.now());
        account.setInviteTokenHash(null);
        account.setInviteTokenExpiresAt(null);
        account.setPortalTokenHash(null);
        account.setPortalTokenExpiresAt(null);
        SupplierPortalAccount saved = repository.save(account);
        auditService.log("SupplierPortalAccount", saved.getId(), "RFQ_INVITE_PROVISION", supplierId.toString(), null, null);
        return saved;
    }

    private void requireCurrentCompanySupplier(Supplier supplier) {
        if (supplier != null && !Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
    }

    private void applySupplierEmail(SupplierPortalAccount entity, Supplier supplier) {
        if (entity.getLoginEmail() != null && !entity.getLoginEmail().isBlank()) {
            return;
        }
        String fallbackEmail = supplierPortalEmail(supplier);
        if (fallbackEmail == null || fallbackEmail.isBlank()) {
            throw new BusinessException("loginEmail is required");
        }
        entity.setLoginEmail(fallbackEmail);
    }

    private String supplierPortalEmail(Supplier supplier) {
        return supplier == null || supplier.getEmail() == null
            ? null
            : supplier.getEmail().trim().toLowerCase(Locale.ROOT);
    }

    private void requireVersion(SupplierPortalAccount account, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required for supplier portal commands");
        }
        if (!Objects.equals(account.getVersion(), expectedVersion)) {
            throw new ConflictException(
                "Supplier portal account was changed by another user; refresh and retry"
            );
        }
    }

    private void requireMeaningfulComment(String value, String field) {
        if (value == null || value.trim().length() < 3) {
            throw new BadRequestException(field + " must contain a meaningful operator explanation");
        }
    }

    private void ensureNotLastActiveSupplierAdmin(SupplierPortalAccount account, String action) {
        if (!isActiveSupplierAdmin(account)) {
            return;
        }
        ensureAnotherActiveSupplierAdmin(account, action);
    }

    private void ensureAnotherActiveSupplierAdmin(SupplierPortalAccount account, String action) {
        long activeAdmins = repository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            account.getTenantId(),
            account.getCompanyId(),
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        );
        if (activeAdmins <= 1) {
            throw new BusinessException("Cannot " + action + " the last active supplier portal admin");
        }
    }

    private boolean isActiveSupplierAdmin(SupplierPortalAccount account) {
        return account != null
            && SupplierPortalRole.SUPPLIER_ADMIN.name().equals(account.getPortalRole())
            && SupplierPortalStatus.ACTIVE.name().equals(account.getStatus());
    }

    @Transactional(readOnly = true)
    public Page<SupplierPortalAccountResponse> search(SupplierPortalAccountSearchRequest request, Pageable pageable) {
        SupplierPortalAccountSearchRequest portalRequest = request == null ? SupplierPortalAccountSearchRequest.empty() : request;
        SearchCriteria filter = SearchCriteria.of(request);
        return repository.findAll(searchSpec(
            portalRequest,
            filter,
            TenantContext.currentTenant(),
            CompanyContext.currentCompany()
        ),
            pageable
        ).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierPortalAccountResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierPortalAccountResponse create(SupplierPortalAccountCreateRequest dto) {
        SupplierPortalAccount entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierPortalAccount saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierPortalAccountResponse update(UUID id, SupplierPortalAccountUpdateRequest dto) {
        SupplierPortalAccount entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        boolean wasActiveAdmin = isActiveSupplierAdmin(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        if (wasActiveAdmin && !SupplierPortalRole.SUPPLIER_ADMIN.name().equals(entity.getPortalRole())) {
            ensureAnotherActiveSupplierAdmin(entity, "demote");
        }
        SupplierPortalAccount saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierPortalAccount entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        ensureNotLastActiveSupplierAdmin(entity, "delete");
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierPortalAccountResponse restore(UUID id) {
        SupplierPortalAccount entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierPortalAccount saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SupplierPortalAccount findEntity(UUID id) {
        SupplierPortalAccount entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private Specification<SupplierPortalAccount> searchSpec(
        SupplierPortalAccountSearchRequest request,
        SearchCriteria filter,
        String tenantId,
        UUID companyId
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (companyId != null) {
                predicates.add(cb.equal(root.get("companyId"), companyId));
            }
            if (!filter.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }

            String searchPattern = searchPattern(filter);
            if (searchPattern != null) {
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), searchPattern),
                    cb.like(cb.lower(root.get("name")), searchPattern)
                ));
            }
            addGreaterThanOrEqualTo(predicates, cb, root.get("createdAt"), filter.fromDateTime());
            addLessThanOrEqualTo(predicates, cb, root.get("createdAt"), filter.toDateTime());
            if (filter.hasStatus()) {
                predicates.add(root.get("status").in(filter.status()));
            }
            if (filter.supplierId() != null) {
                predicates.add(cb.equal(root.get("supplierId"), filter.supplierId()));
            }
            if (request.mfaRequired() != null) {
                predicates.add(cb.equal(root.get("mfaRequired"), request.mfaRequired()));
            }

            addGreaterThanOrEqualTo(predicates, cb, root.get("lastLoginAt"), atStartOfDay(request.lastLoginFrom()));
            addLessThanOrEqualTo(predicates, cb, root.get("lastLoginAt"), atEndOfDay(request.lastLoginTo()));
            addContains(predicates, cb, cb.lower(root.get("loginEmail")), request.loginEmail());
            addContains(predicates, cb, cb.lower(root.get("contactPerson")), request.contactPerson());
            addContains(predicates, cb, cb.lower(root.get("phone")), request.phone());
            addContains(predicates, cb, cb.lower(root.get("allowedIpRange")), request.allowedIpRange());
            addGreaterThanOrEqualTo(predicates, cb, root.get("invitedAt"), atStartOfDay(request.invitedFrom()));
            addLessThanOrEqualTo(predicates, cb, root.get("invitedAt"), atEndOfDay(request.invitedTo()));
            addGreaterThanOrEqualTo(predicates, cb, root.get("activatedAt"), atStartOfDay(request.activatedFrom()));
            addLessThanOrEqualTo(predicates, cb, root.get("activatedAt"), atEndOfDay(request.activatedTo()));
            addGreaterThanOrEqualTo(predicates, cb, root.get("lastSubmissionAt"), atStartOfDay(request.lastSubmissionFrom()));
            addLessThanOrEqualTo(predicates, cb, root.get("lastSubmissionAt"), atEndOfDay(request.lastSubmissionTo()));
            addGreaterThanOrEqualTo(predicates, cb, root.get("termsAcceptedAt"), atStartOfDay(request.termsAcceptedFrom()));
            addLessThanOrEqualTo(predicates, cb, root.get("termsAcceptedAt"), atEndOfDay(request.termsAcceptedTo()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void addContains(
        List<Predicate> predicates,
        jakarta.persistence.criteria.CriteriaBuilder cb,
        jakarta.persistence.criteria.Expression<String> expression,
        String value
    ) {
        String pattern = filterPattern(value);
        if (pattern != null) {
            predicates.add(cb.like(expression, pattern));
        }
    }

    private void addGreaterThanOrEqualTo(
        List<Predicate> predicates,
        jakarta.persistence.criteria.CriteriaBuilder cb,
        jakarta.persistence.criteria.Path<LocalDateTime> expression,
        LocalDateTime value
    ) {
        if (value != null) {
            predicates.add(cb.greaterThanOrEqualTo(expression, value));
        }
    }

    private void addLessThanOrEqualTo(
        List<Predicate> predicates,
        jakarta.persistence.criteria.CriteriaBuilder cb,
        jakarta.persistence.criteria.Path<LocalDateTime> expression,
        LocalDateTime value
    ) {
        if (value != null) {
            predicates.add(cb.lessThanOrEqualTo(expression, value));
        }
    }

    private LocalDateTime atStartOfDay(java.time.LocalDate value) {
        return value == null ? null : value.atStartOfDay();
    }

    private LocalDateTime atEndOfDay(java.time.LocalDate value) {
        return value == null ? null : value.atTime(LocalTime.MAX);
    }

    private String filterPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }
}
