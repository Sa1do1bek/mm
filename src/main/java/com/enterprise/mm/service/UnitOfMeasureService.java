package com.enterprise.mm.service;

import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.UnitOfMeasureMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.payload.UnitOfMeasureResponse;
import com.enterprise.mm.payload.UnitOfMeasureUpdateRequest;
import com.enterprise.mm.payload.UnitOfMeasureSearchRequest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.enterprise.mm.security.AccessScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.repository.UnitOfMeasureRepository;

@Service
@RequiredArgsConstructor
public class UnitOfMeasureService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final UnitOfMeasureRepository repository;
    private final UnitOfMeasureMapper mapper;
    private final AuditService auditService;
    private final String entityName = "UnitOfMeasure";

    @Transactional(readOnly = true)
    public Page<UnitOfMeasureResponse> search(UnitOfMeasureSearchRequest request, Pageable pageable, Locale locale) {
        SearchCriteria filter = SearchCriteria.of(request);
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            pageable
        ).map(entity-> mapper.toResponse(entity,locale));
    }

    @Transactional(readOnly = true)
    public UnitOfMeasureResponse get(UUID id,Locale locale) {
        return mapper.toResponse(findEntity(id),locale);
    }

    @Transactional
    public UnitOfMeasureResponse create(UnitOfMeasureCreateRequest request,Locale locale){
        UnitOfMeasure entity = mapper.toEntity(request);
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setStatus("ACTIVE");
        normalize(entity);
        validateBeforeSave(entity, null);
        UnitOfMeasure saved = repository.save(entity);
        auditService.logInCurrentTransaction(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved,locale);
    }

    @Transactional
    public UnitOfMeasureResponse update(UUID id, UnitOfMeasureUpdateRequest request,Locale locale) {
        UnitOfMeasure entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        int oldQuantityScale = StockUomPolicy.effectiveScale(entity);
        mapper.updateEntity(entity, request);
        normalize(entity);
        validateBeforeSave(entity, id);
        if (StockUomPolicy.effectiveScale(entity) < oldQuantityScale
            && repository.hasStockUsage(entity.getTenantId(), entity.getId())) {
            throw new BadRequestException("Stock quantity precision cannot be reduced after the unit has been used in stock");
        }
        UnitOfMeasure saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved,locale);
    }

    @Transactional
    public void softDelete(UUID id) {
        UnitOfMeasure entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public UnitOfMeasureResponse restore(UUID id, Locale locale) {
        UnitOfMeasure entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        guardOpeningImport(entity.getCompanyId());
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        normalize(entity);
        validateBeforeSave(entity, id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        UnitOfMeasure saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved,locale);
    }

    private UnitOfMeasure findEntity(UUID id) {
        UnitOfMeasure entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(UnitOfMeasure entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(true)
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private void normalize(UnitOfMeasure entity) {
        entity.setCode(trimToNull(firstNonBlank(entity.getCode(), entity.getMeasurementNameShort(), entity.getMeasurementNameLong())));
        entity.setName(trimToNull(firstNonBlank(entity.getName(), entity.getMeasurementNameLong(), entity.getMeasurementNameShort())));
        entity.setMeasurementNameShort(trimToNull(firstNonBlank(entity.getMeasurementNameShort(), entity.getCode(), entity.getMeasurementNameLong())));
        entity.setMeasurementNameLong(trimToNull(firstNonBlank(entity.getMeasurementNameLong(), entity.getName(), entity.getMeasurementNameShort(), entity.getCode())));
        entity.setMeasurementNameShortRu(trimToNull(entity.getMeasurementNameShortRu()));
        entity.setMeasurementNameLongRu(trimToNull(entity.getMeasurementNameLongRu()));
        entity.setMeasurementNameShortUz(trimToNull(entity.getMeasurementNameShortUz()));
        entity.setMeasurementNameLongUz(trimToNull(entity.getMeasurementNameLongUz()));
        if (entity.getUnitType() == null) {
            entity.setUnitType(MeasurementUnitType.MAIN);
        }
    }

    private void validateBeforeSave(UnitOfMeasure entity, UUID existingId) {
        if (entity == null || allBlank(
            entity.getCode(),
            entity.getName(),
            entity.getMeasurementNameShort(),
            entity.getMeasurementNameLong()
        )) {
            throw new BadRequestException("At least one unit value is required");
        }

        if (entity.getStockQuantityScale() != null
            && (entity.getStockQuantityScale() < 0 || entity.getStockQuantityScale() > StockUomPolicy.MAX_QUANTITY_SCALE)) {
            throw new BadRequestException("Stock quantity scale must be between 0 and 7");
        }
        for (String lookup : duplicateLookups(entity)) {
            boolean duplicate = existingId == null
                ? repository.existsByScopedLookup(entity.getTenantId(), entity.getCompanyId(), lookup)
                : repository.existsByScopedLookupExcludingId(
                    entity.getTenantId(), entity.getCompanyId(), lookup, existingId);
            if (duplicate) {
                throw new BadRequestException("Unit of measure already exists: " + lookup);
            }
        }
    }

    private Set<String> duplicateLookups(UnitOfMeasure entity) {
        Set<String> values = new LinkedHashSet<>();
        addLookup(values, entity.getCode());
        addLookup(values, entity.getMeasurementNameShort());
        addLookup(values, entity.getMeasurementNameLong());
        return values;
    }

    private void addLookup(Set<String> values, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            values.add(normalized);
        }
    }

    private boolean allBlank(String... values) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (trimToNull(value) != null) {
                return false;
            }
        }
        return true;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
