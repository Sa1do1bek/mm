package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.LandedCostComponentResponse;
import com.enterprise.mm.payload.LandedCostComponentUpdateRequest;
import com.enterprise.mm.payload.LandedCostComponentCreateRequest;
import com.enterprise.mm.payload.LandedCostComponentSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.LandedCostComponentMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.LandedCostAllocationMethod;
import com.enterprise.mm.enums.LandedCostComponentType;
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.repository.LandedCostComponentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LandedCostComponentService {
    private final String entityName = "LandedCostComponent";
    private final LandedCostComponentRepository repository;
    private final LandedCostComponentMapper mapper;
    private final SupplyLogisticsGuard guard;
    private final ImportDeliveryService importDeliveryService;
    private final AuditService auditService;

    @Transactional
    public LandedCostComponentResponse create(LandedCostComponentCreateRequest dto) {
        LandedCostComponent entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        LandedCostComponent savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        LandedCostComponentResponse saved = mapper.toResponse(savedEntity);
        importDeliveryService.recalculateForImportDelivery(saved.getImportDeliveryId());
        return saved;
    }
    @Transactional
    public LandedCostComponentResponse update(UUID id, LandedCostComponentUpdateRequest dto) {
        LandedCostComponent entity = findEntity(id);
        requireEditable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        LandedCostComponent savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        LandedCostComponentResponse saved = mapper.toResponse(savedEntity);
        importDeliveryService.recalculateForImportDelivery(saved.getImportDeliveryId());
        return saved;
    }
    @Transactional
    public LandedCostComponentResponse post(UUID id) {
        LandedCostComponent component = findEntity(id);
        component.setStatus("POSTED");
        component.setPostedAt(LocalDateTime.now());
        LandedCostComponent saved = repository.save(component);
        importDeliveryService.recalculateForImportDelivery(saved.getImportDeliveryId());
        auditService.log("LandedCostComponent", id, "POST", saved.getComponentType(), null, null);
        return mapper.toResponse(saved);
    }
    protected void beforeCreate(LandedCostComponent entity, LandedCostComponentCreateRequest dto) {
        validate(entity);
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

        if (entity.getStatus() == null || entity.getStatus().isBlank() || "ACTIVE".equals(entity.getStatus())) {
            entity.setStatus("DRAFT");
        }
    }
    protected void beforeUpdate(LandedCostComponent entity, LandedCostComponentUpdateRequest dto) {
        requireEditable(entity);
        validate(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private void requireEditable(LandedCostComponent entity) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("Posted landed cost component cannot be edited");
        }
    }
    private void validate(LandedCostComponent entity) {
        guard.importDelivery(entity.getImportDeliveryId());
        if (entity.getSupplierId() != null) {
            guard.supplier(entity.getSupplierId());
        }
        entity.setComponentType(normalize(entity.getComponentType(), LandedCostComponentType.FREIGHT.name(), enumNames(LandedCostComponentType.values()), "componentType"));
        entity.setAllocationMethod(normalize(entity.getAllocationMethod(), LandedCostAllocationMethod.VALUE.name(), enumNames(LandedCostAllocationMethod.values()), "allocationMethod"));
        entity.setAmount(entity.getAmount() == null ? BigDecimal.ZERO : entity.getAmount());
        if (entity.getAmount().signum() < 0) {
            throw new BusinessException("Landed cost component amount cannot be negative");
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
    }
    private String normalize(String value, String fallback, String[] allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    @Transactional(readOnly = true)
    public Page<LandedCostComponentResponse> search(LandedCostComponentSearchRequest request, Pageable pageable) {
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
            filter.supplierId(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public LandedCostComponentResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public void softDelete(UUID id) {
        LandedCostComponent entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public LandedCostComponentResponse restore(UUID id) {
        LandedCostComponent entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        LandedCostComponent saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected LandedCostComponent findEntity(UUID id) {
        LandedCostComponent entity = repository.findByIdAndIsDeletedFalse(id)
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
