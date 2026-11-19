package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.StockBalanceResponse;
import com.enterprise.mm.payload.StockBalanceUpdateRequest;
import com.enterprise.mm.payload.StockBalanceCreateRequest;
import com.enterprise.mm.payload.WarehouseMaterialBalanceResponse;
import com.enterprise.mm.payload.StockBalanceSearchRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StockBalanceMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class StockBalanceService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final RoleDataScopeService roleDataScopeService;
    private final StockBalanceRepository repository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockBalanceMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "StockBalance";
    private final AuditService auditService;
    private final StockExpiryPolicy expiryPolicy;
    private final com.enterprise.mm.repository.StockBalanceReadQuery readQuery;
    private final com.enterprise.mm.repository.InventoryAccountingSettingsRepository accountingSettings;
    private final com.enterprise.mm.repository.CompanyRepository companies;
    @Transactional(readOnly = true)
    public Page<StockBalanceResponse> search(StockBalanceSearchRequest request, Pageable pageable) {
        StockBalanceSearchRequest criteria = request == null ? StockBalanceSearchRequest.empty() : request;
        roleDataScopeService.warehouseReadScope(criteria.warehouseId(), criteria.warehouseIds());
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(SearchCriteria.of(criteria));
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        java.time.LocalDate expiryAsOfDate = expiryPolicy.today();
        var companyCache = new java.util.HashMap<UUID, java.util.Optional<com.enterprise.mm.entity.Company>>();
        var currencyCache = new java.util.HashMap<UUID, java.util.Optional<String>>();
        return readQuery.search(TenantContext.currentTenant(), scoped ? CompanyContext.currentCompany() : null,
            filter, criteria, expiryAsOfDate, pageable)
            .map(entity -> readResponse(entity, expiryAsOfDate, companyCache, currencyCache));
    }

    public void authorizeRead(StockBalanceSearchRequest criteria) {
        roleDataScopeService.warehouseReadScope(criteria.warehouseId(), criteria.warehouseIds());
    }

    @Transactional(readOnly = true)
    public com.enterprise.mm.payload.WarehouseLayoutStockDtos.Snapshot layoutLocations(UUID warehouseId, String search) {
        var request = new com.enterprise.mm.payload.StockBalanceHttpFilter();
        request.setWarehouseId(warehouseId); request.setSearch(search); request.setPositiveOnly(true);
        var criteria = request.toRequest();
        authorizeRead(criteria);
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(SearchCriteria.of(criteria));
        var locations = readQuery.layoutLocations(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            filter, criteria, expiryPolicy.today());
        return new com.enterprise.mm.payload.WarehouseLayoutStockDtos.Snapshot(java.time.Instant.now(), locations);
    }

    /** Must run inside the export's repeatable-read transaction. */
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void exportRows(StockBalanceSearchRequest criteria, java.time.LocalDate asOfDate, java.util.function.Consumer<StockBalanceResponse> action) {
        authorizeRead(criteria);
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(criteria);
        var companyCache = new java.util.HashMap<UUID, java.util.Optional<com.enterprise.mm.entity.Company>>();
        var currencyCache = new java.util.HashMap<UUID, java.util.Optional<String>>();
        readQuery.stream(TenantContext.currentTenant(), com.enterprise.mm.security.AccessScope.companyScoped(true) ? CompanyContext.currentCompany() : null,
            filter, criteria, asOfDate, entity -> {
                var row = readResponse(entity, asOfDate, companyCache, currencyCache);
                if (roleDataScopeService.canAccessPayload(row)) action.accept(row);
            });
    }

    private StockBalanceResponse readResponse(StockBalance entity, java.time.LocalDate day,
        java.util.Map<UUID, java.util.Optional<com.enterprise.mm.entity.Company>> companyCache,
        java.util.Map<UUID, java.util.Optional<String>> currencyCache) {
        var row = expiryPolicy.enrich(mapper.toResponse(entity), day);
        companyCache.computeIfAbsent(entity.getCompanyId(), id -> companies.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id))
            .ifPresent(company -> { row.setCompanyCode(company.getCode()); row.setCompanyName(company.getName()); });
        row.setCurrencyCode(currencyCache.computeIfAbsent(entity.getCompanyId(), id -> accountingSettings
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .map(com.enterprise.mm.entity.InventoryAccountingSettings::getBaseCurrencyCode)
            .filter(code -> !code.isBlank())).orElse(null));
        // An amount without a known accounting currency is not a displayable book value.
        if (row.getCurrencyCode() == null) row.setTotalValue(null);
        return row;
    }

    @Transactional(readOnly = true)
    public StockBalanceResponse get(UUID id) {
        return readResponse(findEntity(id), expiryPolicy.today(), new java.util.HashMap<>(), new java.util.HashMap<>());
    }

    @Transactional(readOnly = true)
    public List<WarehouseMaterialBalanceResponse> getWarehouseMaterialBalances(UUID warehouseId, List<UUID> materialIds) {
        if (!roleDataScopeService.canAccessWarehouse(warehouseId)) throw new org.springframework.security.access.AccessDeniedException("Warehouse stock access is not assigned");
        Warehouse warehouse = warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + warehouseId));
        if (!TenantContext.currentTenant().equals(warehouse.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId()))) {
            throw new NotFoundException("Warehouse not found: " + warehouseId);
        }

        List<UUID> requestedIds = materialIds == null ? List.of() : materialIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (requestedIds.isEmpty()) {
            return List.of();
        }

        List<Material> materials = new ArrayList<>(requestedIds.size());
        for (UUID materialId : requestedIds) {
            Material material = materialRepository.findByIdAndIsDeletedFalse(materialId)
                .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));
            if (!TenantContext.currentTenant().equals(material.getTenantId())
                || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                    && !com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()))) {
                throw new NotFoundException("Material not found: " + materialId);
            }
            materials.add(material);
        }

        List<StockBalance> balances = repository.findByTenantIdAndCompanyIdAndWarehouseIdAndMaterialIdInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId,
            requestedIds
        );
        Map<UUID, WarehouseMaterialBalanceResponse> aggregated = new LinkedHashMap<>();
        for (Material material : materials) {
            WarehouseMaterialBalanceResponse response = new WarehouseMaterialBalanceResponse();
            response.setMaterialId(material.getId());
            response.setMaterialCode(material.getCode());
            response.setMaterialName(material.getName());
            response.setMaterialStatus(material.getStatus());
            response.setMaterialGroupId(material.getMaterialGroupId());
            response.setUnitOfMeasureId(material.getUnitOfMeasureId());
            response.setWarehouseId(warehouseId);
            response.setQuantity(BigDecimal.ZERO);
            response.setAvailableQuantity(BigDecimal.ZERO);
            response.setReservedQuantity(BigDecimal.ZERO);
            response.setQuarantineQuantity(BigDecimal.ZERO);
            response.setTotalValue(BigDecimal.ZERO);
            aggregated.put(material.getId(), response);
        }

        for (StockBalance balance : balances) {
            WarehouseMaterialBalanceResponse response = aggregated.get(balance.getMaterialId());
            if (response == null) {
                continue;
            }
            response.setQuantity(response.getQuantity().add(defaultDecimal(balance.getQuantity())));
            response.setAvailableQuantity(response.getAvailableQuantity().add(defaultDecimal(balance.getAvailableQuantity())));
            response.setReservedQuantity(response.getReservedQuantity().add(defaultDecimal(balance.getReservedQuantity())));
            response.setQuarantineQuantity(response.getQuarantineQuantity().add(defaultDecimal(balance.getQuarantineQuantity())));
            response.setTotalValue(response.getTotalValue().add(defaultDecimal(balance.getTotalValue())));
            if (response.getBalanceAt() == null || (balance.getBalanceAt() != null && balance.getBalanceAt().isAfter(response.getBalanceAt()))) {
                response.setBalanceAt(balance.getBalanceAt());
            }
        }

        return new ArrayList<>(aggregated.values());
    }
    @Transactional
    public StockBalanceResponse create(StockBalanceCreateRequest dto) {
        StockBalance entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        guardOpeningImport(entity.getCompanyId());
        StockBalance saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public StockBalanceResponse update(UUID id, StockBalanceUpdateRequest dto) {
        StockBalance entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        UUID previousMaterialId = entity.getMaterialId();
        UUID previousWarehouseId = entity.getWarehouseId();
        UUID previousStorageLocationId = entity.getStorageLocationId();
        UUID previousStorageBinId = entity.getStorageBinId();
        UUID previousStockBatchId = entity.getStockBatchId();
        BigDecimal previousQuantity = entity.getQuantity();
        BigDecimal previousAvailableQuantity = entity.getAvailableQuantity();
        BigDecimal previousReservedQuantity = entity.getReservedQuantity();
        BigDecimal previousQuarantineQuantity = entity.getQuarantineQuantity();
        BigDecimal previousTotalValue = entity.getTotalValue();
        mapper.updateEntity(entity, dto);
        rejectManualKeyChange(previousMaterialId, entity.getMaterialId(), "materialId");
        rejectManualKeyChange(previousWarehouseId, entity.getWarehouseId(), "warehouseId");
        rejectManualKeyChange(previousStorageLocationId, entity.getStorageLocationId(), "storageLocationId");
        rejectManualKeyChange(previousStorageBinId, entity.getStorageBinId(), "storageBinId");
        rejectManualKeyChange(previousStockBatchId, entity.getStockBatchId(), "stockBatchId");
        rejectManualFinancialChange(previousQuantity, entity.getQuantity(), "quantity");
        rejectManualFinancialChange(previousAvailableQuantity, entity.getAvailableQuantity(), "availableQuantity");
        rejectManualFinancialChange(previousReservedQuantity, entity.getReservedQuantity(), "reservedQuantity");
        rejectManualFinancialChange(previousQuarantineQuantity, entity.getQuarantineQuantity(), "quarantineQuantity");
        rejectManualFinancialChange(previousTotalValue, entity.getTotalValue(), "totalValue");
        beforeUpdate(entity, dto);
        StockBalance saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        StockBalance entity = findEntity(id);
        guardOpeningImport(entity.getCompanyId());
        String oldSnapshot = auditService.snapshot(entity);
        rejectNonEmptyBalanceDeletion(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public StockBalanceResponse restore(UUID id) {
        StockBalance entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guardOpeningImport(entity.getCompanyId());
        throw new BusinessException("Stock balance restore is not supported; create stock only through a posted movement");
    }
    protected StockBalance findEntity(UUID id) {
        StockBalance entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!roleDataScopeService.canAccessWarehouse(entity.getWarehouseId())) throw new org.springframework.security.access.AccessDeniedException("Warehouse stock access is not assigned");
        return entity;
    }
    protected void beforeCreate(StockBalance entity, StockBalanceCreateRequest dto) {
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
    protected void beforeUpdate(StockBalance entity, StockBalanceUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private String searchPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void rejectManualFinancialSeed(StockBalance entity) {
        if (nonZero(entity.getQuantity())
            || nonZero(entity.getAvailableQuantity())
            || nonZero(entity.getReservedQuantity())
            || nonZero(entity.getQuarantineQuantity())
            || nonZero(entity.getTotalValue())) {
            throw new BusinessException("Stock balance quantities and value must be created by posted stock movements");
        }
    }

    private void rejectNonEmptyBalanceDeletion(StockBalance entity) {
        if (nonZero(entity.getQuantity())
            || nonZero(entity.getAvailableQuantity())
            || nonZero(entity.getReservedQuantity())
            || nonZero(entity.getQuarantineQuantity())
            || nonZero(entity.getTotalValue())) {
            throw new BusinessException("Non-empty stock balance cannot be deleted; post a correcting movement first");
        }
    }

    private void rejectManualKeyChange(UUID before, UUID after, String fieldName) {
        if (!Objects.equals(before, after)) {
            throw new BusinessException("Stock balance " + fieldName + " is immutable; post a transfer or adjustment instead");
        }
    }

    private void rejectManualFinancialChange(BigDecimal before, BigDecimal after, String fieldName) {
        if (defaultDecimal(before).compareTo(defaultDecimal(after)) != 0) {
            throw new BusinessException("Stock balance " + fieldName + " is managed by posted stock movements");
        }
    }

    private boolean nonZero(BigDecimal value) {
        return defaultDecimal(value).signum() != 0;
    }
}
