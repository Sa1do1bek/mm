package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.InventoryOptimizationRecommendationResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationUpdateRequest;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationCreateRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.EoqCalculationRequest;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.InventoryOptimizationRecommendationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandForecast;
import com.enterprise.mm.entity.InventoryOptimizationRecommendation;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.DemandForecastRepository;
import com.enterprise.mm.repository.InventoryOptimizationRecommendationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryOptimizationRecommendationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final InventoryOptimizationRecommendationRepository repository;
    private final InventoryOptimizationRecommendationMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "InventoryOptimizationRecommendation";
    private final MaterialRepository materialRepository;
    private final DemandForecastRepository demandForecastRepository;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    public InventoryOptimizationRecommendationService(
        InventoryOptimizationRecommendationRepository repository,
        InventoryOptimizationRecommendationMapper mapper,
        MaterialCatalogGuard materialCatalogGuard,
        MaterialRepository materialRepository,
        DemandForecastRepository demandForecastRepository,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.materialCatalogGuard = materialCatalogGuard;
        this.materialRepository = materialRepository;
        this.demandForecastRepository = demandForecastRepository;
        this.auditService = auditService;
        this.roleDataScopeService = roleDataScopeService;
    }
    protected void beforeCreate(InventoryOptimizationRecommendation entity, InventoryOptimizationRecommendationCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
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

        if (entity.getMaterialId() == null) {
            throw new BusinessException("Material is required for inventory optimization");
        }
        entity.setStatus("GENERATED");
    }
    @Transactional
    public InventoryOptimizationRecommendationResponse calculateEoq(EoqCalculationRequest request) {
        if (request == null || request.materialId() == null) {
            throw new BusinessException("Material is required for EOQ calculation");
        }
        roleDataScopeService.assertWarehouseAccess(request.warehouseId());
        Material material = materialCatalogGuard.requireActiveTmbMaterial(request.materialId(), "materialId");
        DemandForecast forecast = forecast(request);
        BigDecimal annualDemand = nvl(request.annualDemand());
        BigDecimal averageMonthlyDemand = BigDecimal.ZERO;
        BigDecimal safetyStock = BigDecimal.ZERO;
        BigDecimal reorderPoint = BigDecimal.ZERO;
        if (forecast != null) {
            averageMonthlyDemand = nvl(forecast.getAverageMonthlyDemand());
            annualDemand = annualDemand.signum() > 0 ? annualDemand : averageMonthlyDemand.multiply(BigDecimal.valueOf(12));
            safetyStock = nvl(forecast.getRecommendedSafetyStock());
            reorderPoint = nvl(forecast.getRecommendedReorderPoint());
        }
        if (annualDemand.signum() <= 0) {
            throw new BusinessException("Annual demand must be positive for EOQ calculation");
        }
        BigDecimal unitCost = nvl(request.unitCost());
        BigDecimal holdingCost = request.holdingCostPerUnit();
        if (holdingCost == null || holdingCost.signum() <= 0) {
            if (unitCost.signum() <= 0) {
                throw new BusinessException("Unit cost is required when holding cost per unit is not provided");
            }
            BigDecimal holdingRate = request.holdingRate() == null ? BigDecimal.valueOf(0.15) : request.holdingRate();
            holdingCost = unitCost.multiply(holdingRate).setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal orderCost = request.orderCost() == null ? BigDecimal.valueOf(100) : request.orderCost();
        if (orderCost.signum() <= 0 || holdingCost.signum() <= 0) {
            throw new BusinessException("Order cost and holding cost must be positive");
        }
        BigDecimal eoq = sqrt(BigDecimal.valueOf(2).multiply(annualDemand).multiply(orderCost).divide(holdingCost, 8, RoundingMode.HALF_UP));
        BigDecimal annualOrderingCost = annualDemand.divide(eoq, 8, RoundingMode.HALF_UP).multiply(orderCost).setScale(4, RoundingMode.HALF_UP);
        BigDecimal annualHoldingCost = eoq.divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP).multiply(holdingCost).setScale(4, RoundingMode.HALF_UP);
        BigDecimal annualTotalCost = annualOrderingCost.add(annualHoldingCost).setScale(4, RoundingMode.HALF_UP);
        BigDecimal orderFrequencyDays = eoq.multiply(BigDecimal.valueOf(365)).divide(annualDemand, 4, RoundingMode.HALF_UP);
        int leadTimeDays = request.leadTimeDays() == null ? 30 : Math.max(0, request.leadTimeDays());
        if (reorderPoint.signum() == 0) {
            reorderPoint = annualDemand.divide(BigDecimal.valueOf(365), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(leadTimeDays))
                .add(safetyStock)
                .setScale(4, RoundingMode.HALF_UP);
        }
        InventoryOptimizationRecommendation recommendation = new InventoryOptimizationRecommendation();
        recommendation.setCode("EOQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        recommendation.setName("EOQ " + material.getName());
        recommendation.setStatus("GENERATED");
        recommendation.setMaterialId(material.getId());
        recommendation.setWarehouseId(request.warehouseId());
        recommendation.setDemandForecastId(forecast == null ? request.demandForecastId() : forecast.getId());
        recommendation.setAnnualDemand(annualDemand);
        recommendation.setAverageMonthlyDemand(averageMonthlyDemand);
        recommendation.setOrderCost(orderCost);
        recommendation.setHoldingCostPerUnit(holdingCost);
        recommendation.setUnitCost(unitCost);
        recommendation.setHoldingRate(request.holdingRate());
        recommendation.setEoqQuantity(eoq);
        recommendation.setOrderFrequencyDays(orderFrequencyDays);
        recommendation.setAnnualOrderingCost(annualOrderingCost);
        recommendation.setAnnualHoldingCost(annualHoldingCost);
        recommendation.setAnnualTotalCost(annualTotalCost);
        recommendation.setLeadTimeDays(leadTimeDays);
        recommendation.setServiceLevel(request.serviceLevel() == null ? BigDecimal.valueOf(0.95) : request.serviceLevel());
        recommendation.setRecommendedSafetyStock(safetyStock);
        recommendation.setRecommendedReorderPoint(reorderPoint);
        recommendation.setRecommendedMinStock(safetyStock);
        recommendation.setRecommendedMaxStock(reorderPoint.add(eoq).setScale(4, RoundingMode.HALF_UP));
        recommendation.setGeneratedAt(LocalDateTime.now());
        InventoryOptimizationRecommendation saved = repository.save(recommendation);
        auditService.log("InventoryOptimizationRecommendation", saved.getId(), "CALCULATE_EOQ", saved.getEoqQuantity().toPlainString(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryOptimizationRecommendationResponse applyToMaterial(UUID id) {
        InventoryOptimizationRecommendation recommendation = findEntity(id);
        Material material = materialCatalogGuard.requireActiveTmbMaterial(recommendation.getMaterialId(), "materialId");
        guardOpeningImport(material.getCompanyId());
        material.setSafetyStock(nvl(recommendation.getRecommendedSafetyStock()));
        material.setReorderPoint(nvl(recommendation.getRecommendedReorderPoint()));
        material.setMinStock(nvl(recommendation.getRecommendedMinStock()));
        material.setMaxStock(nvl(recommendation.getRecommendedMaxStock()));
        materialRepository.save(material);
        recommendation.setStatus("APPLIED");
        recommendation.setAppliedAt(LocalDateTime.now());
        InventoryOptimizationRecommendation saved = repository.save(recommendation);
        auditService.log("InventoryOptimizationRecommendation", id, "APPLY_TO_MATERIAL", material.getCode(), null, null);
        return mapper.toResponse(saved);
    }
    private DemandForecast forecast(EoqCalculationRequest request) {
        if (request.demandForecastId() != null) {
            return demandForecastRepository.findByIdAndIsDeletedFalse(request.demandForecastId())
                .filter(item -> !item.isDeleted())
                .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
                .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Demand forecast not found: " + request.demandForecastId()));
        }
        return demandForecastRepository.findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalseOrderByGeneratedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.materialId(),
            request.warehouseId()
        ).orElse(null);
    }
    private BigDecimal sqrt(BigDecimal value) {
        return BigDecimal.valueOf(Math.sqrt(value.doubleValue())).setScale(4, RoundingMode.HALF_UP);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<InventoryOptimizationRecommendationResponse> search(InventoryOptimizationRecommendationSearchRequest request, Pageable pageable) {
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
    public InventoryOptimizationRecommendationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public InventoryOptimizationRecommendationResponse create(InventoryOptimizationRecommendationCreateRequest dto) {
        InventoryOptimizationRecommendation entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        InventoryOptimizationRecommendation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public InventoryOptimizationRecommendationResponse update(UUID id, InventoryOptimizationRecommendationUpdateRequest dto) {
        InventoryOptimizationRecommendation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        InventoryOptimizationRecommendation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        InventoryOptimizationRecommendation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public InventoryOptimizationRecommendationResponse restore(UUID id) {
        InventoryOptimizationRecommendation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        InventoryOptimizationRecommendation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected InventoryOptimizationRecommendation findEntity(UUID id) {
        InventoryOptimizationRecommendation entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeUpdate(InventoryOptimizationRecommendation entity, InventoryOptimizationRecommendationUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}

