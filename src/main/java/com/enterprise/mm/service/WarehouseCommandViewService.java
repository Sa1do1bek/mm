package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.AllowedModuleKey;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.BalanceRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.DecimalMetric;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.FixedAssetRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.InventoryMetrics;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.LocationRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.MoneyMetric;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.MovementRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.NonMovingRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.OverviewPreviews;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.ReservationRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.Risk;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.RiskKey;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.RiskSeverity;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.ScopeInfo;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.TabKey;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.Visibility;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.WarehouseCommandViewResponse;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.WarehouseHeader;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.MaterialReservationRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AccessScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseCommandViewService {
    private static final PageRequest PREVIEW = PageRequest.of(0, 5);
    private static final List<String> ACTIVE_RESERVATION_STATUSES = List.of("ACTIVE", "PARTIALLY_RELEASED");

    private final WarehouseRepository warehouseRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final MaterialReservationRepository materialReservationRepository;
    private final NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository;
    private final InventoryAccountingSettingsRepository accountingSettingsRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final Clock materialCommandViewClock;
    private final StockBalanceService balanceReads;

    public WarehouseCommandViewResponse summary(UUID warehouseId) {
        Scope scope = requireWarehouse(warehouseId);
        Instant asOf = materialCommandViewClock.instant();
        String currency = currency(scope);
        var aggregate = stockBalanceRepository.aggregateWarehouse360(
            scope.tenantId(), scope.companyId(), scope.warehouseId());
        long locationCount = storageLocationRepository.countWarehouse360Locations(
            scope.tenantId(), scope.companyId(), scope.warehouseId());

        InventoryMetrics inventory = inventoryMetrics(aggregate, currency, locationCount);
        Page<BalanceRow> balances = balances(scope, true, PREVIEW, currency);
        Page<FixedAssetRow> fixedAssets = fixedAssets(scope, PREVIEW);
        Page<LocationRow> locations = locations(scope, PREVIEW);
        Page<MovementRow> movements = movements(scope, null, null, PREVIEW);
        Page<ReservationRow> reservations = reservations(scope, PREVIEW);
        Page<NonMovingRow> nonMoving = nonMoving(scope, PREVIEW);

        List<Risk> risks = risks(scope, inventory, nonMoving.getTotalElements());
        Map<TabKey, Visibility> permissions = allReadable();
        Map<TabKey, Long> counts = new EnumMap<>(TabKey.class);
        counts.put(TabKey.BALANCES, balances.getTotalElements());
        counts.put(TabKey.FIXED_ASSETS, fixedAssets.getTotalElements());
        counts.put(TabKey.LOCATIONS, locations.getTotalElements());
        counts.put(TabKey.MOVEMENTS, movements.getTotalElements());
        counts.put(TabKey.RESERVATIONS, reservations.getTotalElements());
        counts.put(TabKey.NON_MOVING, nonMoving.getTotalElements());

        return new WarehouseCommandViewResponse(
            header(scope.warehouse()),
            new ScopeInfo(scope.tenantId(), scope.companyId(), scope.warehouseId()),
            asOf,
            permissions,
            inventory,
            risks,
            Map.copyOf(counts),
            new OverviewPreviews(
                balances.getContent(),
                fixedAssets.getContent(),
                locations.getContent(),
                movements.getContent(),
                reservations.getContent(),
                nonMoving.getContent()));
    }

    public Page<BalanceRow> balances(UUID warehouseId, UUID storageLocationId, boolean positiveOnly, Pageable pageable) {
        Scope scope = requireWarehouse(warehouseId);
        return balances(scope, positiveOnly, pageable, currency(scope), storageLocationId);
    }

    public Page<BalanceRow> balances(UUID warehouseId, UUID storageLocationId, boolean positiveOnly,
        List<com.enterprise.mm.enums.StockExpiryStatus> expiryStatus, Boolean expiryWarning, Pageable pageable) {
        Scope scope = requireWarehouse(warehouseId);
        return balances(scope, positiveOnly, pageable, currency(scope), storageLocationId, expiryStatus, expiryWarning);
    }

    public Page<LocationRow> locations(UUID warehouseId, Pageable pageable) {
        return locations(requireWarehouse(warehouseId), pageable);
    }

    public Page<MovementRow> movements(UUID warehouseId, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        return movements(requireWarehouse(warehouseId), dateFrom, dateTo, pageable);
    }

    public Page<ReservationRow> reservations(UUID warehouseId, Pageable pageable) {
        return reservations(requireWarehouse(warehouseId), pageable);
    }

    public Page<NonMovingRow> nonMoving(UUID warehouseId, Pageable pageable) {
        return nonMoving(requireWarehouse(warehouseId), pageable);
    }

    private Page<BalanceRow> balances(Scope scope, boolean positiveOnly, Pageable pageable, String currency) {
        return balances(scope, positiveOnly, pageable, currency, null);
    }

    private Page<BalanceRow> balances(
        Scope scope, boolean positiveOnly, Pageable pageable, String currency, UUID storageLocationId
    ) {
        return balances(scope, positiveOnly, pageable, currency, storageLocationId, null, null);
    }

    private Page<BalanceRow> balances(Scope scope, boolean positiveOnly, Pageable pageable, String currency,
        UUID storageLocationId, List<com.enterprise.mm.enums.StockExpiryStatus> expiryStatus, Boolean expiryWarning) {
        var options = new com.enterprise.mm.payload.StockBalanceViewOptions(); options.setPositiveOnly(positiveOnly);
        var request = com.enterprise.mm.payload.StockBalanceSearchRequest.forWarehouse(scope.warehouseId(),storageLocationId,expiryStatus,expiryWarning,options);
        return balanceReads.search(request,bounded(pageable)).map(row -> BalanceRow.from(row,currency));
    }

    public Page<BalanceRow> balances(UUID warehouseId, com.enterprise.mm.payload.StockBalanceSearchRequest request,
        com.enterprise.mm.payload.StockBalanceViewOptions options, Pageable pageable) {
        Scope scope=requireWarehouse(warehouseId);
        if(options.getPositiveOnly()==null)options.setPositiveOnly(true);
        String currency=currency(scope);
        return balanceReads.search(request.withWarehouseId(warehouseId).withOptions(options),bounded(pageable))
            .map(row -> BalanceRow.from(row,currency));
    }

    private Page<LocationRow> locations(Scope scope, Pageable pageable) {
        return storageLocationRepository.findWarehouse360Locations(
                scope.tenantId(), scope.companyId(), scope.warehouseId(), bounded(pageable))
            .map(this::mapLocation);
    }

    private Page<FixedAssetRow> fixedAssets(Scope scope, Pageable pageable) {
        return fixedAssetRepository
            .findByTenantIdAndCompanyIdAndWarehouseIdAndCustodyKindAndIsDeletedFalse(
                scope.tenantId(),
                scope.companyId(),
                scope.warehouseId(),
                FixedAssetCustodyKind.WAREHOUSE,
                bounded(pageable))
            .map(this::mapFixedAsset);
    }

    private FixedAssetRow mapFixedAsset(FixedAsset asset) {
        String materialName = asset.getMaterial() == null ? asset.getName() : asset.getMaterial().getName();
        return new FixedAssetRow(
            asset.getId(),
            asset.getMaterialId(),
            materialName,
            asset.getInventoryNumber(),
            asset.getSerialNumber(),
            asset.getLifecycleStatus() == null ? null : asset.getLifecycleStatus().name(),
            asset.getCustodyKind() == null ? null : asset.getCustodyKind().name(),
            asset.getOperatingLocation(),
            LinkTarget.of(
                AllowedModuleKey.FIXED_ASSETS.key(),
                null,
                Map.of("assetId", asset.getId().toString())));
    }

    private Page<MovementRow> movements(Scope scope, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        return stockMovementRepository.findWarehouse360Movements(
                scope.tenantId(),
                scope.companyId(),
                scope.warehouseId(),
                StockMovementStatus.effectiveStatusNames(),
                dateFrom,
                dateTo,
                bounded(pageable))
            .map(row -> new MovementRow(
                row.getId(),
                row.getPostedAt(),
                row.getMovementDate(),
                row.getMovementType() == null ? null : row.getMovementType().name(),
                row.getStatus(),
                decimal(row.getQuantity()),
                row.getUnitOfMeasureCode(),
            row.getUnitOfMeasureShortName(),
                row.getMaterialId(),
                row.getMaterialCode(),
                row.getMaterialName(),
                row.getBatchNumber(),
                row.getReferenceType(),
                row.getReferenceId(),
                LinkTarget.of(AllowedModuleKey.STOCK_MOVEMENTS.key(), row.getId(), Map.of())));
    }

    private Page<ReservationRow> reservations(Scope scope, Pageable pageable) {
        return materialReservationRepository.findWarehouse360Reservations(
                scope.tenantId(),
                scope.companyId(),
                scope.warehouseId(),
                ACTIVE_RESERVATION_STATUSES,
                bounded(pageable))
            .map(row -> new ReservationRow(
                row.getId(),
                row.getCode(),
                row.getStatus(),
                row.getMaterialId(),
                row.getMaterialCode(),
                row.getMaterialName(),
                decimal(row.getQuantity()),
                decimal(row.getReservedQuantity()),
                row.getExpiresAt(),
                row.getReservationType(),
                row.getPriority(),
                LinkTarget.of(AllowedModuleKey.MATERIAL_RESERVATIONS.key(), row.getId(), Map.of())));
    }

    private Page<NonMovingRow> nonMoving(Scope scope, Pageable pageable) {
        return nonMovingStockCaseLineRepository.findWarehouse360OpenCases(
                scope.tenantId(), scope.companyId(), scope.warehouseId(), bounded(pageable))
            .map(row -> new NonMovingRow(
                row.getId(),
                row.getCaseId(),
                row.getCaseNo(),
                row.getCaseName(),
                row.getCaseStatus(),
                row.getMaterialId(),
                row.getMaterialCode(),
                row.getMaterialName(),
                row.getDetectedAt(),
                LinkTarget.of(
                    AllowedModuleKey.NON_MOVING_STOCK_DASHBOARD.key(),
                    row.getCaseId(),
                    Map.of("warehouseId", scope.warehouseId().toString()))));
    }

    private InventoryMetrics inventoryMetrics(
        StockBalanceRepository.Warehouse360AggregateProjection aggregate,
        String currency,
        long locationCount
    ) {
        if (aggregate == null) {
            return new InventoryMetrics(
                DecimalMetric.noData(), DecimalMetric.noData(), DecimalMetric.noData(), DecimalMetric.noData(),
                MoneyMetric.noData(), 0, locationCount, 0);
        }
        return new InventoryMetrics(
            DecimalMetric.available(aggregate.getOnHand()),
            DecimalMetric.available(aggregate.getAvailable()),
            DecimalMetric.available(aggregate.getReserved()),
            DecimalMetric.available(aggregate.getQuarantine()),
            currency == null
                ? MoneyMetric.noData()
                : MoneyMetric.available(aggregate.getTotalValue(), currency),
            aggregate.getMaterialCount(),
            locationCount,
            aggregate.getPositiveBalanceCount());
    }

    private List<Risk> risks(Scope scope, InventoryMetrics inventory, long openNonMoving) {
        List<Risk> risks = new ArrayList<>();
        String status = scope.warehouse().getStatus();
        if (status != null && !"ACTIVE".equalsIgnoreCase(status)) {
            risks.add(new Risk(
                RiskKey.INACTIVE,
                RiskSeverity.HIGH,
                "warehouse360.risk.inactive",
                "WAREHOUSE",
                scope.warehouseId(),
                LinkTarget.of(AllowedModuleKey.WAREHOUSES.key(), scope.warehouseId(), Map.of())));
        }
        BigDecimal quarantine = parse(inventory.quarantine());
        BigDecimal onHand = parse(inventory.onHand());
        if (quarantine != null && quarantine.signum() > 0
            && onHand != null && onHand.signum() > 0
            && quarantine.multiply(BigDecimal.TEN).compareTo(onHand) >= 0) {
            risks.add(new Risk(
                RiskKey.HIGH_QUARANTINE,
                RiskSeverity.HIGH,
                "warehouse360.risk.high_quarantine",
                "STOCK_BALANCE",
                null,
                LinkTarget.of(
                    AllowedModuleKey.STOCK_BALANCES.key(),
                    null,
                    Map.of("warehouseId", scope.warehouseId().toString()))));
        }
        if (openNonMoving > 0) {
            risks.add(new Risk(
                RiskKey.NON_MOVING,
                RiskSeverity.MEDIUM,
                "warehouse360.risk.non_moving",
                "NON_MOVING_STOCK_CASE",
                null,
                LinkTarget.of(
                    AllowedModuleKey.NON_MOVING_STOCK_DASHBOARD.key(),
                    null,
                    Map.of("warehouseId", scope.warehouseId().toString()))));
        }
        if (onHand != null && onHand.signum() == 0 && inventory.materialCount() == 0) {
            risks.add(new Risk(
                RiskKey.ZERO_STOCK,
                RiskSeverity.LOW,
                "warehouse360.risk.zero_stock",
                "WAREHOUSE",
                scope.warehouseId(),
                LinkTarget.of(AllowedModuleKey.WAREHOUSES.key(), scope.warehouseId(), Map.of())));
        }
        return List.copyOf(risks);
    }

    private LocationRow mapLocation(StorageLocation location) {
        return new LocationRow(
            location.getId(),
            location.getCode(),
            location.getName(),
            location.getStatus(),
            location.getZoneCode(),
            location.getBinCode(),
            location.getStorageZoneType() == null ? null : location.getStorageZoneType().name(),
            location.getTemperatureZone(),
            location.isAllowMixedMaterials(),
            LinkTarget.of(AllowedModuleKey.STORAGE_LOCATIONS.key(), location.getId(), Map.of()));
    }

    private WarehouseHeader header(Warehouse warehouse) {
        return new WarehouseHeader(
            warehouse.getId(),
            warehouse.getCode(),
            warehouse.getName(),
            warehouse.getStatus(),
            warehouse.getAddress(),
            warehouse.getDescription());
    }

    private Scope requireWarehouse(UUID warehouseId) {
        String tenantId = TenantContext.currentTenant();
        Warehouse warehouse = warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, warehouseId)
            .filter(candidate -> AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException(
                "Warehouse not found: " + warehouseId,
                "error.entity.not_found",
                "Warehouse",
                warehouseId));
        if (!roleDataScopeService.canAccessWarehouse(warehouse.getId())) {
            throw new AccessDeniedException("warehouse360.domain.denied");
        }
        UUID companyId = warehouse.getCompanyId() != null
            ? warehouse.getCompanyId()
            : CompanyContext.currentCompany();
        return new Scope(tenantId, companyId, warehouse.getId(), warehouse);
    }

    private String currency(Scope scope) {
        return accountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId())
            .map(InventoryAccountingSettings::getBaseCurrencyCode)
            .filter(code -> !code.isBlank())
            .orElse(null);
    }

    private Map<TabKey, Visibility> allReadable() {
        Map<TabKey, Visibility> map = new EnumMap<>(TabKey.class);
        for (TabKey key : TabKey.values()) {
            map.put(key, Visibility.READ);
        }
        return Map.copyOf(map);
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, 25);
        }
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(pageable.getPageSize(), 100));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal parse(DecimalMetric metric) {
        if (metric == null
            || metric.value() == null
            || (metric.status() != MetricStatus.AVAILABLE && metric.status() != MetricStatus.STALE)) {
            return null;
        }
        try {
            return new BigDecimal(metric.value());
        } catch (Exception ex) {
            return null;
        }
    }

    private record Scope(String tenantId, UUID companyId, UUID warehouseId, Warehouse warehouse) {
    }
}
