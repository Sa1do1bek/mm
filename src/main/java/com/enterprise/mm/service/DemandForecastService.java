package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.security.AccessScope;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.DemandForecastResponse;
import com.enterprise.mm.payload.DemandForecastUpdateRequest;
import com.enterprise.mm.payload.DemandForecastCreateRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.DemandForecastRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.EoqCalculationRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.ForecastBatchRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.ForecastBatchResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DemandForecastMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandForecastMethod;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.entity.DemandForecast;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.repository.DemandForecastRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
import com.enterprise.mm.payload.DemandForecastSearchRequest;

@Service
@RequiredArgsConstructor
public class DemandForecastService {
    private final DemandForecastRepository repository;
    private final DemandForecastMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "DemandForecast";
    private static final int DEFAULT_HISTORY_MONTHS = 12;
    private static final int DEFAULT_FORECAST_MONTHS = 3;
    private final DemandForecastRepository demandForecastRepository;
    private final DemandForecastMapper demandForecastMapper;
    private final MaterialRepository materialRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryOptimizationRecommendationService optimizationRecommendationService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    protected void beforeCreate(DemandForecast entity, DemandForecastCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
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

        if (entity.getMaterialId() == null) {
            throw new BusinessException("Material is required for demand forecast");
        }
        if (entity.getForecastMethod() == null || entity.getForecastMethod().isBlank()) {
            entity.setForecastMethod(DemandForecastMethod.MOVING_AVERAGE.name());
        }
    }
    @Transactional
    public DemandForecastResponse generate(DemandForecastRequest request) {
        return generateInternal(request);
    }
    @Transactional
    public ForecastBatchResult generateBatch(ForecastBatchRequest request) {
        ForecastBatchRequest batchRequest = request == null
            ? new ForecastBatchRequest(null, null, null, null, null, null, null, null, true, false)
            : request;
        List<Material> materials = materialRepository.findByTenantIdAndStatusAndIsDeletedFalse(TenantContext.currentTenant(), MaterialStatus.ACTIVE.name()).stream()
            .filter(material -> com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()))
            .toList();
        List<UUID> forecastIds = new ArrayList<>();
        List<UUID> recommendationIds = new ArrayList<>();
        int applied = 0;
        for (Material material : materials) {
            DemandForecastResponse forecast = generateInternal(new DemandForecastRequest(
                material.getId(),
                batchRequest.warehouseId(),
                batchRequest.forecastMethod(),
                batchRequest.historyMonths(),
                batchRequest.forecastMonths(),
                batchRequest.leadTimeDays(),
                batchRequest.serviceLevel()
            ));
            forecastIds.add(forecast.getId());
            if (batchRequest.calculateEoq() && nvl(forecast.getAnnualizedForecastQuantity()).signum() > 0) {
                BigDecimal unitCost = nvl(material.getAverageCost()).signum() > 0
                    ? material.getAverageCost()
                    : receiptUnitCost(material.getId(), batchRequest.warehouseId());
                if (unitCost.signum() <= 0) {
                    // No known unit cost basis (no average cost, no receipt history) — skip EOQ for this
                    // material rather than failing the whole batch.
                    continue;
                }
                try {
                    var recommendation = optimizationRecommendationService.calculateEoq(new EoqCalculationRequest(
                        material.getId(),
                        batchRequest.warehouseId(),
                        forecast.getId(),
                        null,
                        batchRequest.orderCost(),
                        null,
                        unitCost,
                        batchRequest.holdingRate(),
                        batchRequest.leadTimeDays(),
                        batchRequest.serviceLevel()
                    ));
                    recommendationIds.add(recommendation.getId());
                    if (batchRequest.applyToMaterial()) {
                        optimizationRecommendationService.applyToMaterial(recommendation.getId());
                        applied++;
                    }
                } catch (BusinessException ex) {
                    // Skip materials that cannot produce a valid EOQ recommendation instead of aborting
                    // the entire batch run.
                }
            }
        }
        auditService.log("DemandForecast", null, "GENERATE_BATCH", "materials=" + materials.size() + ", forecasts=" + forecastIds.size() + ", eoq=" + recommendationIds.size(), null, null);
        return new ForecastBatchResult(materials.size(), forecastIds.size(), recommendationIds.size(), applied, forecastIds, recommendationIds);
    }
    private DemandForecastResponse generateInternal(DemandForecastRequest request) {
        roleDataScopeService.assertWarehouseAccess(request.warehouseId());
        if (request == null || request.materialId() == null) {
            throw new BusinessException("Material is required for demand forecast");
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.materialId())
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + request.materialId()));
        int historyMonths = bounded(request.historyMonths(), DEFAULT_HISTORY_MONTHS, 1, 36);
        int forecastMonths = bounded(request.forecastMonths(), DEFAULT_FORECAST_MONTHS, 1, 24);
        DemandForecastMethod method = parseMethod(request.forecastMethod());
        LocalDate periodEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
        LocalDate periodStart = YearMonth.from(periodEnd).minusMonths(historyMonths - 1L).atDay(1);
        Map<YearMonth, BigDecimal> monthlyDemand = monthlyDemand(request.materialId(), request.warehouseId(), periodStart.atStartOfDay());
        BigDecimal average = average(monthlyDemand.values().stream().toList());
        BigDecimal weighted = weightedAverage(monthlyDemand.values().stream().toList());
        BigDecimal seasonalityIndex = seasonalityIndex(monthlyDemand, average);
        BigDecimal monthlyForecast = switch (method) {
            case MOVING_AVERAGE -> average;
            case WEIGHTED_MOVING_AVERAGE -> weighted;
            case SEASONAL_INDEX -> average.multiply(seasonalityIndex).setScale(4, RoundingMode.HALF_UP);
        };
        BigDecimal forecastQuantity = monthlyForecast.multiply(BigDecimal.valueOf(forecastMonths)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal annualizedForecast = monthlyForecast.multiply(BigDecimal.valueOf(12)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal stdDev = standardDeviation(monthlyDemand.values().stream().toList(), average);
        BigDecimal variation = average.signum() == 0 ? BigDecimal.ZERO : stdDev.divide(average, 4, RoundingMode.HALF_UP);
        BigDecimal confidence = BigDecimal.ONE.subtract(variation.min(BigDecimal.ONE)).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        int leadTimeDays = request.leadTimeDays() == null ? 30 : Math.max(0, request.leadTimeDays());
        BigDecimal serviceLevel = request.serviceLevel() == null ? BigDecimal.valueOf(0.95) : request.serviceLevel();
        BigDecimal safetyStock = stdDev.multiply(zScore(serviceLevel)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal leadTimeDemand = monthlyForecast.multiply(BigDecimal.valueOf(leadTimeDays)).divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        BigDecimal reorderPoint = leadTimeDemand.add(safetyStock).setScale(4, RoundingMode.HALF_UP);
        BigDecimal minStock = safetyStock;
        BigDecimal maxStock = reorderPoint.add(monthlyForecast.max(BigDecimal.ONE)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal trendSlope = trendSlope(monthlyDemand.values().stream().toList());
        DemandForecast forecast = new DemandForecast();
        forecast.setCode("FC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        forecast.setName("Forecast " + material.getName());
        forecast.setStatus("GENERATED");
        forecast.setMaterialId(request.materialId());
        forecast.setWarehouseId(request.warehouseId());
        forecast.setForecastMethod(method.name());
        forecast.setPeriodStart(periodStart);
        forecast.setPeriodEnd(periodEnd);
        forecast.setHistoryMonths(historyMonths);
        forecast.setForecastMonths(forecastMonths);
        forecast.setAverageMonthlyDemand(average);
        forecast.setForecastQuantity(forecastQuantity);
        forecast.setAnnualizedForecastQuantity(annualizedForecast);
        forecast.setDemandStdDev(stdDev);
        forecast.setVariationCoefficient(variation);
        forecast.setConfidence(confidence);
        forecast.setModelQuality(modelQuality(confidence, monthlyDemand.size()));
        forecast.setTrendSlope(trendSlope);
        forecast.setSeasonalityIndex(seasonalityIndex);
        forecast.setLeadTimeDays(leadTimeDays);
        forecast.setServiceLevel(serviceLevel);
        forecast.setLeadTimeDemand(leadTimeDemand);
        forecast.setRecommendedSafetyStock(safetyStock);
        forecast.setRecommendedReorderPoint(reorderPoint);
        forecast.setRecommendedMinStock(minStock);
        forecast.setRecommendedMaxStock(maxStock);
        forecast.setGeneratedAt(LocalDateTime.now());
        forecast.setSource("STOCK_MOVEMENTS");
        DemandForecast saved = repository.save(forecast);
        auditService.log("DemandForecast", saved.getId(), "GENERATE", method.name(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional(readOnly = true)
    public DemandForecastResponse latest(UUID materialId, UUID warehouseId) {
        return repository.findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalseOrderByGeneratedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                materialId,
                warehouseId
            )
            .map(mapper::toResponse)
            .orElseThrow(() -> new NotFoundException("Demand forecast not found"));
    }
    private Map<YearMonth, BigDecimal> monthlyDemand(UUID materialId, UUID warehouseId, LocalDateTime from) {
        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        YearMonth cursor = YearMonth.from(from.toLocalDate());
        YearMonth end = YearMonth.now().minusMonths(1);
        while (!cursor.isAfter(end)) {
            result.put(cursor, BigDecimal.ZERO);
            cursor = cursor.plusMonths(1);
        }
        List<StockMovement> movements = stockMovementRepository.findByTenantIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(
            TenantContext.currentTenant(),
            StockMovementStatus.effectiveStatusNames(),
            List.of(MovementType.ISSUE, MovementType.WRITE_OFF),
            from
        );
        for (StockMovement movement : movements) {
            if (!com.enterprise.mm.security.AccessScope.canAccessCompany(movement.getCompanyId())) {
                continue;
            }
            if (!materialId.equals(movement.getMaterialId())) {
                continue;
            }
            if (warehouseId != null && !warehouseId.equals(movement.getWarehouseId())) {
                continue;
            }
            if (movement.getPostedAt() == null) {
                continue;
            }
            YearMonth month = YearMonth.from(movement.getPostedAt());
            if (result.containsKey(month)) {
                result.put(month, result.get(month).add(nvl(movement.getQuantity())));
            }
        }
        return result;
    }
    private BigDecimal receiptUnitCost(UUID materialId, UUID warehouseId) {
        List<StockMovement> receipts = stockMovementRepository.findByTenantIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(
            TenantContext.currentTenant(),
            StockMovementStatus.effectiveStatusNames(),
            List.of(MovementType.RECEIPT),
            LocalDateTime.now().minusYears(50)
        );
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (StockMovement movement : receipts) {
            if (!com.enterprise.mm.security.AccessScope.canAccessCompany(movement.getCompanyId())) {
                continue;
            }
            if (!materialId.equals(movement.getMaterialId())) {
                continue;
            }
            if (warehouseId != null && !warehouseId.equals(movement.getWarehouseId())) {
                continue;
            }
            BigDecimal quantity = nvl(movement.getQuantity());
            totalQuantity = totalQuantity.add(quantity);
            totalCost = totalCost.add(quantity.multiply(nvl(movement.getUnitCost())));
        }
        if (totalQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return totalCost.divide(totalQuantity, 4, RoundingMode.HALF_UP);
    }
    private DemandForecastMethod parseMethod(String value) {
        if (value == null || value.isBlank()) {
            return DemandForecastMethod.MOVING_AVERAGE;
        }
        return DemandForecastMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
    private int bounded(Integer value, int defaultValue, int min, int max) {
        int candidate = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, candidate));
    }
    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }
    private BigDecimal weightedAverage(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal weight = BigDecimal.valueOf(i + 1L);
            weightedSum = weightedSum.add(values.get(i).multiply(weight));
            weightTotal = weightTotal.add(weight);
        }
        return weightedSum.divide(weightTotal, 4, RoundingMode.HALF_UP);
    }
    private BigDecimal seasonalityIndex(Map<YearMonth, BigDecimal> monthlyDemand, BigDecimal average) {
        if (average.signum() == 0 || monthlyDemand.isEmpty()) {
            return BigDecimal.ONE;
        }
        YearMonth nextMonth = YearMonth.now();
        BigDecimal sameMonthDemand = monthlyDemand.entrySet().stream()
            .filter(entry -> entry.getKey().getMonth() == nextMonth.getMonth())
            .map(Map.Entry::getValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        long sameMonthCount = monthlyDemand.keySet().stream().filter(month -> month.getMonth() == nextMonth.getMonth()).count();
        if (sameMonthCount == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal seasonalAverage = sameMonthDemand.divide(BigDecimal.valueOf(sameMonthCount), 4, RoundingMode.HALF_UP);
        return seasonalAverage.divide(average, 4, RoundingMode.HALF_UP);
    }
    private BigDecimal trendSlope(List<BigDecimal> values) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        return values.get(values.size() - 1)
            .subtract(values.get(0))
            .divide(BigDecimal.valueOf(values.size() - 1L), 4, RoundingMode.HALF_UP);
    }
    private String modelQuality(BigDecimal confidence, int dataPoints) {
        if (dataPoints < 6 || confidence.compareTo(BigDecimal.valueOf(0.50)) < 0) {
            return "LOW";
        }
        if (dataPoints >= 12 && confidence.compareTo(BigDecimal.valueOf(0.80)) >= 0) {
            return "HIGH";
        }
        return "MEDIUM";
    }
    private BigDecimal standardDeviation(List<BigDecimal> values, BigDecimal average) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        double variance = values.stream()
            .map(value -> value.subtract(average).doubleValue())
            .mapToDouble(delta -> delta * delta)
            .sum() / values.size();
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
    }
    private BigDecimal zScore(BigDecimal serviceLevel) {
        if (serviceLevel == null) {
            return BigDecimal.valueOf(1.65);
        }
        if (serviceLevel.compareTo(BigDecimal.valueOf(0.99)) >= 0) {
            return BigDecimal.valueOf(2.33);
        }
        if (serviceLevel.compareTo(BigDecimal.valueOf(0.95)) >= 0) {
            return BigDecimal.valueOf(1.65);
        }
        if (serviceLevel.compareTo(BigDecimal.valueOf(0.90)) >= 0) {
            return BigDecimal.valueOf(1.28);
        }
        return BigDecimal.ONE;
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<DemandForecastResponse> search(DemandForecastSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            DemandForecast.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public DemandForecastResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DemandForecastResponse create(DemandForecastCreateRequest dto) {
        DemandForecast entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        DemandForecast saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public DemandForecastResponse update(UUID id, DemandForecastUpdateRequest dto) {
        DemandForecast entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        DemandForecast saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        DemandForecast entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DemandForecastResponse restore(UUID id) {
        DemandForecast entity = repository.findAnyById(id)
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
        DemandForecast saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected DemandForecast findEntity(UUID id) {
        DemandForecast entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeUpdate(DemandForecast entity, DemandForecastUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}
