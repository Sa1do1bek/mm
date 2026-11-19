package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.StockBatchResponse;
import com.enterprise.mm.payload.StockBatchUpdateRequest;
import com.enterprise.mm.payload.StockBatchCreateRequest;
import com.enterprise.mm.payload.StockBatchSearchRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StockBatchMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.repository.StockBatchRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockBatchService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final StockBatchRepository repository;
    private final StockBatchMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "StockBatch";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    @Transactional(readOnly = true)
    public Page<StockBatchResponse> search(StockBatchSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
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
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public StockBatchResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public StockBatchResponse create(StockBatchCreateRequest dto) {
        StockBatch entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        guardOpeningImport(entity.getCompanyId());
        StockBatch saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public StockBatchResponse update(UUID id, StockBatchUpdateRequest dto) {
        StockBatch entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        BigDecimal previousQuantity = entity.getQuantity();
        BigDecimal previousUnitCost = entity.getUnitCost();
        BigDecimal previousPurchaseUnitCost = entity.getPurchaseUnitCost();
        BigDecimal previousAdditionalCostPerUnit = entity.getAdditionalCostPerUnit();
        BigDecimal previousFinalUnitCost = entity.getFinalUnitCost();
        mapper.updateEntity(entity, dto);
        rejectManualFinancialChange(previousQuantity, entity.getQuantity(), "quantity");
        rejectManualFinancialChange(previousUnitCost, entity.getUnitCost(), "unitCost");
        rejectManualFinancialChange(previousPurchaseUnitCost, entity.getPurchaseUnitCost(), "purchaseUnitCost");
        rejectManualFinancialChange(previousAdditionalCostPerUnit, entity.getAdditionalCostPerUnit(), "additionalCostPerUnit");
        rejectManualFinancialChange(previousFinalUnitCost, entity.getFinalUnitCost(), "finalUnitCost");
        beforeUpdate(entity, dto);
        StockBatch saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        StockBatch entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public StockBatchResponse restore(UUID id) {
        StockBatch entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guardOpeningImport(entity.getCompanyId());
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        StockBatch saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected StockBatch findEntity(UUID id) {
        StockBatch entity = repository.findByIdAndIsDeletedFalse(id)
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
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }
    protected void beforeCreate(StockBatch entity, StockBatchCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        rejectManualFinancialSeed(entity);
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
    }
    protected void beforeUpdate(StockBatch entity, StockBatchUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void rejectManualFinancialSeed(StockBatch entity) {
        if (positive(entity.getQuantity()) || positive(entity.getUnitCost())
            || positive(entity.getPurchaseUnitCost()) || positive(entity.getAdditionalCostPerUnit()) || positive(entity.getFinalUnitCost())) {
            throw new BusinessException("Stock batch quantity and unit cost must be created by posted stock movements");
        }
    }

    private void rejectManualFinancialChange(BigDecimal before, BigDecimal after, String fieldName) {
        if (nvl(before).compareTo(nvl(after)) != 0) {
            throw new BusinessException("Stock batch " + fieldName + " is managed by posted stock movements");
        }
    }

    private boolean positive(BigDecimal value) {
        return nvl(value).signum() > 0;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
