package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.InventoryFxDifferenceResponse;
import com.enterprise.mm.payload.InventoryFxDifferenceUpdateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceCreateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.InventoryFxDifferenceMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryFxDifference;
import com.enterprise.mm.repository.InventoryFxDifferenceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryFxDifferenceService {
    private final String entityName = "InventoryFxDifference";
    private final InventoryFxDifferenceRepository repository;
    private final InventoryFxDifferenceMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    protected void beforeCreate(InventoryFxDifference entity, InventoryFxDifferenceCreateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
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

        entity.setStatus("CALCULATED");
        calculateValues(entity);
    }
    protected void beforeUpdate(InventoryFxDifference entity, InventoryFxDifferenceUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED FX difference cannot be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus("CALCULATED");
        calculateValues(entity);
    }
    @Transactional
    public InventoryFxDifferenceResponse recalculate(UUID id) {
        InventoryFxDifference entity = findEntity(id);
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED FX difference cannot be recalculated");
        }
        calculateValues(entity);
        entity.setStatus("CALCULATED");
        InventoryFxDifference saved = repository.save(entity);
        auditService.log("InventoryFxDifference", saved.getId(), "RECALCULATE", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryFxDifferenceResponse post(UUID id) {
        InventoryFxDifference entity = findEntity(id);
        if (!"CALCULATED".equals(entity.getStatus())) {
            throw new BusinessException("Only CALCULATED FX difference can be posted");
        }
        calculateValues(entity);
        entity.setStatus("POSTED");
        entity.setPostedAt(LocalDateTime.now());
        InventoryFxDifference saved = repository.save(entity);
        integrationEventService.emit("INVENTORY.FX_DIFFERENCE_POSTED", "InventoryFxDifference", saved.getId(), Map.ofEntries(
            Map.entry("periodStart", saved.getPeriodStart()),
            Map.entry("periodEnd", saved.getPeriodEnd()),
            Map.entry("referenceType", saved.getReferenceType() == null ? "" : saved.getReferenceType()),
            Map.entry("referenceId", saved.getReferenceId() == null ? "" : saved.getReferenceId()),
            Map.entry("documentCurrencyCode", saved.getDocumentCurrencyCode()),
            Map.entry("baseCurrencyCode", saved.getBaseCurrencyCode()),
            Map.entry("foreignAmount", saved.getForeignAmount()),
            Map.entry("documentRate", saved.getDocumentRate()),
            Map.entry("closingRate", saved.getClosingRate()),
            Map.entry("differenceAmount", saved.getDifferenceAmount()),
            Map.entry("differenceType", saved.getDifferenceType())
        ));
        auditService.log("InventoryFxDifference", saved.getId(), "POST", saved.getDifferenceType(), null, null);
        return mapper.toResponse(saved);
    }
    private void calculateValues(InventoryFxDifference entity) {
        if (entity.getPeriodStart() == null) {
            entity.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        }
        if (entity.getPeriodEnd() == null) {
            entity.setPeriodEnd(LocalDate.now());
        }
        if (entity.getPeriodEnd().isBefore(entity.getPeriodStart())) {
            throw new BusinessException("Period end cannot be before period start");
        }
        if (entity.getDocumentDate() == null) {
            entity.setDocumentDate(entity.getPeriodEnd());
        }
        entity.setDocumentCurrencyCode(normalizeToken(entity.getDocumentCurrencyCode(), "USD"));
        entity.setBaseCurrencyCode(normalizeToken(entity.getBaseCurrencyCode(), "UZS"));
        entity.setForeignAmount(nvl(entity.getForeignAmount()));
        entity.setDocumentRate(nvl(entity.getDocumentRate()));
        entity.setClosingRate(nvl(entity.getClosingRate()));
        if (entity.getForeignAmount().signum() < 0 || entity.getDocumentRate().signum() <= 0 || entity.getClosingRate().signum() <= 0) {
            throw new BusinessException("FX amount and rates must be non-negative, rates must be greater than zero");
        }
        entity.setBaseAmount(entity.getForeignAmount().multiply(entity.getDocumentRate()).setScale(4, RoundingMode.HALF_UP));
        entity.setRevaluedBaseAmount(entity.getForeignAmount().multiply(entity.getClosingRate()).setScale(4, RoundingMode.HALF_UP));
        entity.setDifferenceAmount(entity.getRevaluedBaseAmount().subtract(entity.getBaseAmount()).setScale(4, RoundingMode.HALF_UP));
        int sign = entity.getDifferenceAmount().signum();
        entity.setDifferenceType(sign == 0 ? "NONE" : sign > 0 ? "LOSS" : "GAIN");
    }
    private String normalizeToken(String value, String fallback) {
        return (value == null || value.isBlank() ? fallback : value).trim().toUpperCase(Locale.ROOT);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<InventoryFxDifferenceResponse> search(InventoryFxDifferenceSearchRequest request, Pageable pageable) {
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
            filter.warehouseId(),
            filter.supplierId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            filter.referenceId(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public InventoryFxDifferenceResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public InventoryFxDifferenceResponse create(InventoryFxDifferenceCreateRequest dto) {
        InventoryFxDifference entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryFxDifference saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryFxDifferenceResponse update(UUID id, InventoryFxDifferenceUpdateRequest dto) {
        InventoryFxDifference entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        InventoryFxDifference saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryFxDifference entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryFxDifferenceResponse restore(UUID id) {
        InventoryFxDifference entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryFxDifference saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireMutable(InventoryFxDifference entity) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED FX difference is immutable; create a reversal or correction");
        }
    }
    protected InventoryFxDifference findEntity(UUID id) {
        InventoryFxDifference entity = repository.findByIdAndIsDeletedFalse(id)
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
