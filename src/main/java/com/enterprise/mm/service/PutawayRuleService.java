package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.PutawayRuleResponse;
import com.enterprise.mm.payload.PutawayRuleUpdateRequest;
import com.enterprise.mm.payload.PutawayRuleCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.WmsDtos.PutawayRecommendationDto;
import com.enterprise.mm.payload.WmsDtos.PutawayRecommendationRequest;
import com.enterprise.mm.payload.PutawayRuleSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PutawayRuleMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PutawayRule;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PutawayRuleRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PutawayRuleService {
    private final PutawayRuleRepository repository;
    private final PutawayRuleMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "PutawayRule";
    private final AuditService auditService;
    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final RoleDataScopeService roleDataScopeService;

    protected void beforeCreate(PutawayRule entity, PutawayRuleCreateRequest dto) {
        assertRuleWarehouse(entity);
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
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

        if (entity.getPriority() == null) {
            entity.setPriority(100);
        }
        if (entity.getWarehouseId() == null && entity.getTargetStorageLocationId() == null) {
            throw new BusinessException("Warehouse or target storage location is required for putaway rule");
        }
        entity.setAllowMixedMaterials(dto == null || dto.isAllowMixedMaterials());
    }
    @Transactional(readOnly = true)
    public PutawayRecommendationDto recommend(PutawayRecommendationRequest request) {
        if (request != null) roleDataScopeService.assertWarehouseAccess(request.warehouseId());
        if (request == null || request.materialId() == null) {
            throw new BusinessException("Material is required for putaway recommendation");
        }
        if (request.warehouseId() == null) {
            throw new BusinessException("Warehouse is required for putaway recommendation");
        }
        BigDecimal quantity = nvl(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Quantity must be positive for putaway recommendation");
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.materialId())
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + request.materialId()));
        List<PutawayRule> rules = repository.findActiveForWarehouse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.warehouseId() == null,
            request.warehouseId()
        );
        for (PutawayRule rule : rules) {
            if (!matchesRule(rule, material, request)) {
                continue;
            }
            PutawayRecommendationDto recommendation = locationForRule(rule, material, request, quantity);
            if (recommendation != null) {
                return recommendation;
            }
        }
        return fallbackLocation(material, request, quantity);
    }
    private boolean matchesRule(PutawayRule rule, Material material, PutawayRecommendationRequest request) {
        if (rule.getMaterialId() != null && !rule.getMaterialId().equals(material.getId())) {
            return false;
        }
        if (rule.getMaterialGroupId() != null && !rule.getMaterialGroupId().equals(material.getMaterialGroupId())) {
            return false;
        }
        String hazardClass = firstNonBlank(request.hazardClass(), material.getHazardClass());
        if (rule.getHazardClass() != null && !equalsIgnoreCase(rule.getHazardClass(), hazardClass)) {
            return false;
        }
        if (rule.getTemperatureZone() != null && request.temperatureZone() != null && !equalsIgnoreCase(rule.getTemperatureZone(), request.temperatureZone())) {
            return false;
        }
        if (rule.getAbcClass() != null && request.abcClass() != null && !equalsIgnoreCase(rule.getAbcClass(), request.abcClass())) {
            return false;
        }
        if (rule.getMinShelfLifeDays() != null && request.shelfLifeDays() != null && request.shelfLifeDays() < rule.getMinShelfLifeDays()) {
            return false;
        }
        return request.crossDockAllowed() || !rule.isAllowCrossDock();
    }
    private PutawayRecommendationDto locationForRule(PutawayRule rule, Material material, PutawayRecommendationRequest request, BigDecimal quantity) {
        List<StorageLocation> candidates = storageLocationRepository.findByTenantIdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), request.warehouseId())
            .stream()
            .filter(location -> com.enterprise.mm.security.AccessScope.canAccessCompany(location.getCompanyId()))
            .filter(location -> "ACTIVE".equals(location.getStatus()))
            .filter(location -> rule.getTargetStorageLocationId() == null || rule.getTargetStorageLocationId().equals(location.getId()))
            .filter(location -> rule.getStorageZoneCode() == null || equalsIgnoreCase(rule.getStorageZoneCode(), location.getZoneCode()))
            .sorted(Comparator.comparing(StorageLocation::getZoneCode, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(StorageLocation::getBinCode, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(StorageLocation::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
        for (StorageLocation location : candidates) {
            PutawayRecommendationDto recommendation = evaluateLocation(rule, material, request, quantity, location);
            if (recommendation != null) {
                return recommendation;
            }
        }
        return null;
    }
    private PutawayRecommendationDto evaluateLocation(
        PutawayRule rule,
        Material material,
        PutawayRecommendationRequest request,
        BigDecimal quantity,
        StorageLocation location
    ) {
        String hazardClass = firstNonBlank(request.hazardClass(), material.getHazardClass());
        if (location.getHazardClassAllowed() != null && hazardClass != null && !equalsIgnoreCase(location.getHazardClassAllowed(), hazardClass)) {
            return null;
        }
        if (location.getTemperatureZone() != null && request.temperatureZone() != null && !equalsIgnoreCase(location.getTemperatureZone(), request.temperatureZone())) {
            return null;
        }
        boolean mixedAllowed = rule.isAllowMixedMaterials() && location.isAllowMixedMaterials();
        if (!mixedAllowed && stockBalanceRepository.countOtherMaterialsInStorageLocation(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.warehouseId(),
            location.getId(),
            request.materialId()
        ) > 0) {
            return null;
        }
        BigDecimal occupied = nvl(stockBalanceRepository.sumQuantityByStorageLocation(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.warehouseId(),
            location.getId()
        ));
        BigDecimal capacity = firstNonNull(rule.getMaxQuantity(), location.getCapacityQuantity());
        BigDecimal remaining = capacity == null ? null : capacity.subtract(occupied);
        if (remaining != null && remaining.compareTo(quantity) < 0) {
            return null;
        }
        String reason = "rule " + rule.getName() + ", priority " + rule.getPriority();
        return new PutawayRecommendationDto(
            rule.getId(),
            location.getId(),
            location.getName(),
            location.getZoneCode(),
            quantity,
            occupied,
            remaining,
            reason
        );
    }
    private PutawayRecommendationDto fallbackLocation(Material material, PutawayRecommendationRequest request, BigDecimal quantity) {
        return storageLocationRepository.findByTenantIdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), request.warehouseId())
            .stream()
            .filter(location -> com.enterprise.mm.security.AccessScope.canAccessCompany(location.getCompanyId()))
            .filter(location -> "ACTIVE".equals(location.getStatus()))
            .map(location -> evaluateLocation(emptyRule(), material, request, quantity, location))
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new BusinessException("No suitable storage location found for putaway"));
    }
    private PutawayRule emptyRule() {
        PutawayRule rule = new PutawayRule();
        rule.setName("fallback");
        rule.setPriority(9999);
        rule.setAllowMixedMaterials(true);
        return rule;
    }
    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim().toUpperCase(Locale.ROOT);
        }
        return second == null || second.isBlank() ? null : second.trim().toUpperCase(Locale.ROOT);
    }
    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first == null ? second : first;
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<PutawayRuleResponse> search(PutawayRuleSearchRequest request, Pageable pageable) {
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
        ).map(this::toResponse);
    }
    @Transactional(readOnly = true)
    public PutawayRuleResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public PutawayRuleResponse create(PutawayRuleCreateRequest dto) {
        PutawayRule entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        PutawayRule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public PutawayRuleResponse update(UUID id, PutawayRuleUpdateRequest dto) {
        PutawayRule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        PutawayRule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PutawayRule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PutawayRuleResponse restore(UUID id) {
        PutawayRule entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        assertRuleWarehouse(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PutawayRule saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected PutawayRule findEntity(UUID id) {
        PutawayRule entity = repository.findByIdAndIsDeletedFalse(id)
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
        assertRuleWarehouse(entity);
        return entity;
    }
    protected void beforeUpdate(PutawayRule entity, PutawayRuleUpdateRequest dto) {
        assertRuleWarehouse(entity);
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void assertRuleWarehouse(PutawayRule entity) {
        UUID warehouseId = entity.getWarehouseId();
        if (warehouseId == null && entity.getTargetStorageLocationId() != null
            && roleDataScopeService.effectiveWarehouseId(null) != null) {
            warehouseId = storageLocationRepository.findByIdAndIsDeletedFalse(entity.getTargetStorageLocationId())
                .filter(location -> TenantContext.currentTenant().equals(location.getTenantId()))
                .filter(location -> com.enterprise.mm.security.AccessScope.canAccessCompany(location.getCompanyId()))
                .map(StorageLocation::getWarehouseId).orElse(null);
        }
        roleDataScopeService.assertWarehouseAccess(warehouseId);
    }

    private PutawayRuleResponse toResponse(PutawayRule entity) {
        PutawayRuleResponse response = mapper.toResponse(entity);
        if (response == null) {
            return null;
        }
        String tenantId = TenantContext.currentTenant();
        if (response.getMaterialId() != null) {
            materialRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, response.getMaterialId())
                .filter(material -> com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()))
                .map(Material::getName)
                .ifPresent(response::setMaterialName);
        }
        if (response.getMaterialGroupId() != null) {
            materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    tenantId,
                    entity.getCompanyId(),
                    response.getMaterialGroupId()
                )
                .map(MaterialGroup::getName)
                .ifPresent(response::setMaterialGroupName);
        }
        if (response.getTargetStorageLocationId() != null) {
            storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    tenantId,
                    entity.getCompanyId(),
                    response.getTargetStorageLocationId()
                )
                .map(StorageLocation::getName)
                .ifPresent(response::setTargetStorageLocationName);
        }
        return response;
    }

    protected boolean companyScoped() {
        return true;
    }
}
