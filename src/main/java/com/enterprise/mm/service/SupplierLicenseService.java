package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierLicenseResponse;
import com.enterprise.mm.payload.SupplierLicenseUpdateRequest;
import com.enterprise.mm.payload.SupplierLicenseCreateRequest;
import com.enterprise.mm.payload.SupplierLicenseSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierLicenseMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierLicenseService {
    private static final int RENEWAL_HORIZON_DAYS = 30;
    private static final String ACTIVE = "ACTIVE";
    private static final String EXPIRED = "EXPIRED";
    private static final String SUSPENDED = "SUSPENDED";
    private static final String REVOKED = "REVOKED";

    private final SupplierLicenseRepository repository;
    private final SupplierLicenseMapper mapper;
    private final String entityName = "SupplierLicense";
    private final AuditService auditService;
    private final SupplierRepository supplierRepository;

    protected void beforeCreate(SupplierLicense entity, SupplierLicenseCreateRequest dto) {
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setStatus(ACTIVE);
        entity.setRenewalRequired(false);
        entity.setRevokedAt(null);
        entity.setRevocationReason(null);
        normalizeEditableFields(entity);
        validateSupplier(entity);
        validateDates(entity);
        validateUniqueLicenseNumber(entity);
        applyLifecycleFacts(entity);
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    protected void beforeUpdate(SupplierLicense entity, SupplierLicenseUpdateRequest dto) {
        normalizeEditableFields(entity);
        validateSupplier(entity);
        validateDates(entity);
        validateUniqueLicenseNumber(entity);
        applyLifecycleFacts(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private Supplier validateSupplier(SupplierLicense entity) {
        if (entity.getSupplierId() == null) {
            throw new BusinessException("supplierId is required");
        }
        Supplier supplier = supplierRepository
            .findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getSupplierId())
            .orElseThrow(() -> new BusinessException("Supplier not found"));
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        return supplier;
    }

    private void validateDates(SupplierLicense entity) {
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("validTo must be greater than or equal to validFrom");
        }
        if (entity.getIssueDate() != null && entity.getValidFrom() != null && entity.getIssueDate().isAfter(entity.getValidFrom())) {
            throw new BusinessException("issueDate must be before or equal to validFrom");
        }
        if (entity.getIssueDate() != null && entity.getValidTo() != null && entity.getIssueDate().isAfter(entity.getValidTo())) {
            throw new BusinessException("issueDate must be before or equal to validTo");
        }
        if (entity.getRevokedAt() != null && entity.getIssueDate() != null && entity.getRevokedAt().isBefore(entity.getIssueDate())) {
            throw new BusinessException("revokedAt must be after or equal to issueDate");
        }
        if (entity.getRevokedAt() != null && entity.getValidFrom() != null && entity.getRevokedAt().isBefore(entity.getValidFrom())) {
            throw new BusinessException("revokedAt must be after or equal to validFrom");
        }
        if (entity.getRevokedAt() != null && isBlank(entity.getRevocationReason())) {
            throw new BusinessException("revocationReason is required for a revoked license");
        }
        if (entity.getRevokedAt() == null && !isBlank(entity.getRevocationReason())) {
            throw new BusinessException("revocationReason requires revokedAt");
        }
        if (REVOKED.equalsIgnoreCase(entity.getStatus()) && entity.getRevokedAt() == null) {
            throw new BusinessException("revokedAt is required for a revoked license");
        }
    }

    private void validateUniqueLicenseNumber(SupplierLicense entity) {
        if (isBlank(entity.getLicenseNumber())) {
            throw new BusinessException("licenseNumber is required");
        }
        repository.findActiveByNormalizedLicenseNumber(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getSupplierId(),
                entity.getLicenseNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Supplier license number already exists");
            });
    }

    private void normalizeEditableFields(SupplierLicense entity) {
        entity.setCode(trimToNull(entity.getCode()));
        entity.setName(trimToNull(entity.getName()));
        entity.setLicenseNumber(trimToNull(entity.getLicenseNumber()));
        entity.setLicenseType(trimToNull(entity.getLicenseType()));
        entity.setIssuingAuthority(trimToNull(entity.getIssuingAuthority()));
        entity.setScope(trimToNull(entity.getScope()));
        entity.setTerritory(trimToNull(entity.getTerritory()));
        if (isBlank(entity.getCode()) && !isBlank(entity.getLicenseNumber())) {
            entity.setCode(entity.getLicenseNumber());
        }
        if (isBlank(entity.getName())) {
            entity.setName(!isBlank(entity.getLicenseType()) ? entity.getLicenseType() : "Supplier license");
        }
    }

    private void applyLifecycleFacts(SupplierLicense entity) {
        LocalDate today = LocalDate.now();
        if (entity.getRevokedAt() != null) {
            entity.setStatus(REVOKED);
            entity.setRenewalRequired(false);
            return;
        }
        if (!SUSPENDED.equalsIgnoreCase(entity.getStatus())) {
            entity.setStatus(entity.getValidTo() != null && entity.getValidTo().isBefore(today)
                ? EXPIRED
                : ACTIVE);
        }
        entity.setRenewalRequired(entity.getValidTo() != null
            && !entity.getValidTo().isAfter(today.plusDays(RENEWAL_HORIZON_DAYS)));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
    @Transactional(readOnly = true)
    public Page<SupplierLicenseResponse> search(SupplierLicenseSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            normalizeFilter(request.licenseType()),
            request.renewalRequired(),
            request.validFrom(),
            request.validTo(),
            pageable
        ).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierLicenseResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierLicenseResponse create(SupplierLicenseCreateRequest dto) {
        SupplierLicense entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierLicense saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierLicenseResponse update(UUID id, SupplierLicenseUpdateRequest dto) {
        SupplierLicense entity = findMutationEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierLicense saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierLicense entity = findMutationEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierLicenseResponse restore(UUID id) {
        SupplierLicense entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireCurrentCompany(entity);
        if (!entity.isDeleted()) {
            throw new BusinessException(entityName + " is not deleted: " + id);
        }
        normalizeEditableFields(entity);
        validateSupplier(entity);
        validateDates(entity);
        validateUniqueLicenseNumber(entity);
        applyLifecycleFacts(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierLicense saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SupplierLicense findEntity(UUID id) {
        SupplierLicense entity = repository.findByIdAndIsDeletedFalse(id)
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

    private SupplierLicense findMutationEntity(UUID id) {
        SupplierLicense entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void requireCurrentCompany(SupplierLicense entity) {
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }
}
