package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierRelatedPartyResponse;
import com.enterprise.mm.payload.SupplierRelatedPartyUpdateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyCreateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartySearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierRelatedPartyMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierKycRiskLevel;
import com.enterprise.mm.enums.SupplierRelatedPartyType;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierRelatedPartyService {
    private final String entityName = "SupplierRelatedParty";
    private final AuditService auditService;
    private final SupplierRelatedPartyRepository repository;
    private final SupplierRelatedPartyMapper mapper;
    private final SupplierComplianceService complianceService;
    @Transactional
    public SupplierRelatedPartyResponse create(SupplierRelatedPartyCreateRequest dto) {
        SupplierRelatedParty entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierRelatedParty savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        SupplierRelatedPartyResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    @Transactional
    public SupplierRelatedPartyResponse update(UUID id, SupplierRelatedPartyUpdateRequest dto) {
        SupplierRelatedParty entity = findMutationEntity(id);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierRelatedParty savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        SupplierRelatedPartyResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    protected void beforeCreate(SupplierRelatedParty entity, SupplierRelatedPartyCreateRequest dto) {
        var supplier = complianceService.validateSupplier(entity.getSupplierId());
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        normalize(entity);
        entity.setStatus("ACTIVE");
        entity.setCompanyId(CompanyContext.currentCompany());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(SupplierRelatedParty entity, SupplierRelatedPartyUpdateRequest dto) {
        complianceService.validateSupplier(entity.getSupplierId());
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void normalize(SupplierRelatedParty entity) {
        entity.setPartyType(complianceService.normalizeCode(entity.getPartyType(), SupplierRelatedPartyType.BENEFICIAL_OWNER.name(), "partyType", enumNames(SupplierRelatedPartyType.values())));
        entity.setRiskLevel(complianceService.normalizeCode(entity.getRiskLevel(), SupplierKycRiskLevel.LOW.name(), "riskLevel", enumNames(SupplierKycRiskLevel.values())));
        if (entity.getOwnershipPercent() == null) {
            entity.setOwnershipPercent(BigDecimal.ZERO);
        }
        if (entity.getOwnershipPercent().compareTo(BigDecimal.ZERO) < 0 || entity.getOwnershipPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("ownershipPercent must be between 0 and 100");
        }
        if (entity.getValidFrom() != null && entity.getValidTo() != null && entity.getValidTo().isBefore(entity.getValidFrom())) {
            throw new BusinessException("validTo must be greater than or equal to validFrom");
        }
        if (entity.isSanctionsMatch()) {
            entity.setRiskLevel(SupplierKycRiskLevel.CRITICAL.name());
        } else if ((entity.isPepFlag() || entity.isConflictOfInterestFlag()) && SupplierKycRiskLevel.LOW.name().equals(entity.getRiskLevel())) {
            entity.setRiskLevel(SupplierKycRiskLevel.HIGH.name());
        }
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    @Transactional(readOnly = true)
    public Page<SupplierRelatedPartyResponse> search(SupplierRelatedPartySearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        String taxId = normalizeTaxId(request.taxId());
        if (taxId != null) {
            return filterByTaxId(pageable, filter, scoped, request, taxId).map(mapper::toResponse);
        }
        Page<SupplierRelatedParty> page = repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            SearchCriteria.normalizedToken(request.partyType()),
            SearchCriteria.normalizedToken(request.riskLevel()),
            request.fullName(),
            request.legalName(),
            request.countryCode(),
            request.positionTitle(),
            request.phone(),
            request.email(),
            request.pepFlag(),
            request.sanctionsMatch(),
            request.conflictOfInterestFlag(),
            request.ownershipMin(),
            request.ownershipMax(),
            request.validFrom(),
            request.validTo(),
            pageable
        );
        return page.map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierRelatedPartyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierRelatedParty entity = findMutationEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
        complianceService.applySupplierRisk(entity.getSupplierId());
    }
    @Transactional
    public SupplierRelatedPartyResponse restore(UUID id) {
        SupplierRelatedParty entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireCurrentCompany(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierRelatedParty saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return mapper.toResponse(saved);
    }
    protected SupplierRelatedParty findEntity(UUID id) {
        SupplierRelatedParty entity = repository.findByIdAndIsDeletedFalse(id)
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

    private SupplierRelatedParty findMutationEntity(UUID id) {
        SupplierRelatedParty entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void requireCurrentCompany(SupplierRelatedParty entity) {
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private Page<SupplierRelatedParty> filterByTaxId(
        Pageable pageable,
        SearchCriteria filter,
        boolean scoped,
        SupplierRelatedPartySearchRequest request,
        String taxId
    ) {
        int batchSize = Math.max(pageable.getPageSize(), 100);
        int pageNumber = 0;
        long offset = pageable.getOffset();
        ArrayList<SupplierRelatedParty> matches = new ArrayList<>();
        long totalMatches = 0;
        Page<SupplierRelatedParty> batch;
        do {
            batch = repository.search(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                filter.includeDeleted(),
                searchPattern(filter),
                filter.fromDateTime(),
                filter.toDateTime(),
                filter.status(),
                !filter.hasStatus(),
                filter.supplierId(),
                SearchCriteria.normalizedToken(request.partyType()),
                SearchCriteria.normalizedToken(request.riskLevel()),
                request.fullName(),
                request.legalName(),
                request.countryCode(),
                request.positionTitle(),
                request.phone(),
                request.email(),
                request.pepFlag(),
                request.sanctionsMatch(),
                request.conflictOfInterestFlag(),
                request.ownershipMin(),
                request.ownershipMax(),
                request.validFrom(),
                request.validTo(),
                PageRequest.of(pageNumber++, batchSize, pageable.getSort())
            );
            for (SupplierRelatedParty entity : batch.getContent()) {
                if (!containsIgnoreCase(entity.getTaxId(), taxId)) {
                    continue;
                }
                if (totalMatches >= offset && matches.size() < pageable.getPageSize()) {
                    matches.add(entity);
                }
                totalMatches++;
            }
        } while (batch.hasNext());
        return new PageImpl<>(matches, pageable, totalMatches);
    }

    private String normalizeTaxId(String taxId) {
        if (taxId == null) {
            return null;
        }
        String normalized = taxId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean containsIgnoreCase(String value, String search) {
        if (value == null || search == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }
}
