package com.enterprise.mm.service;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AllowedModuleKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AvailabilityFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AvailabilityRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MovementFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MovementRow;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialInventoryQueryService {
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryAccountingSettingsRepository accountingSettingsRepository;

    public InventorySnapshot aggregate(MaterialScope scope) {
        UUID warehouseId = scope.warehouseId();
        StockBalanceRepository.Material360AggregateProjection projection = stockBalanceRepository.aggregateMaterial360(
            scope.tenantId(), scope.companyId(), scope.materialId(), warehouseId);
        String currencyCode = accountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId())
            .map(InventoryAccountingSettings::getBaseCurrencyCode)
            .orElse(null);
        return new InventorySnapshot(
            projection.getOnHand(),
            projection.getAvailable(),
            projection.getReserved(),
            projection.getQuarantine(),
            projection.getTotalValue(),
            currencyCode,
            projection.getWarehouseCount(),
            projection.getBalanceAt());
    }

    public Page<AvailabilityRow> availability(MaterialScope scope, AvailabilityFilter filter, Pageable pageable) {
        AvailabilityFilter effectiveFilter = filter == null
            ? new AvailabilityFilter(null, null, null, false)
            : filter;
        UUID warehouseId = scope.warehouseId() == null ? effectiveFilter.warehouseId() : scope.warehouseId();
        String currencyCode = accountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId())
            .map(InventoryAccountingSettings::getBaseCurrencyCode)
            .orElse(null);
        return stockBalanceRepository.findMaterial360Availability(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                warehouseId,
                effectiveFilter.storageLocationId(),
                effectiveFilter.stockBatchId(),
                effectiveFilter.positiveOnly(),
                bounded(pageable))
            .map(projection -> mapAvailability(projection, currencyCode));
    }

    public Page<MovementRow> movements(MaterialScope scope, MovementFilter filter, Pageable pageable) {
        MovementFilter effectiveFilter = filter == null
            ? new MovementFilter(null, null, null, null, null)
            : filter;
        UUID warehouseId = scope.warehouseId() == null ? effectiveFilter.warehouseId() : scope.warehouseId();
        return stockMovementRepository.findMaterial360Movements(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                StockMovementStatus.effectiveStatusNames(),
                effectiveFilter.dateFrom(),
                effectiveFilter.dateTo(),
                warehouseId,
                effectiveFilter.movementType(),
                effectiveFilter.stockBatchId(),
                bounded(pageable))
            .map(this::mapMovement);
    }

    private AvailabilityRow mapAvailability(
        StockBalanceRepository.Material360AvailabilityProjection projection,
        String currencyCode
    ) {
        return new AvailabilityRow(
            projection.getId(),
            projection.getWarehouseId(),
            projection.getWarehouseName(),
            projection.getStorageLocationId(),
            projection.getStorageLocationName(),
            projection.getStockBatchId(),
            projection.getBatchNumber(),
            decimal(projection.getOnHandQuantity()),
            decimal(projection.getAvailableQuantity()),
            decimal(projection.getReservedQuantity()),
            decimal(projection.getQuarantineQuantity()),
            currencyCode == null ? null : decimal(projection.getTotalValue()),
            currencyCode,
            projection.getBalanceAt(),
            LinkTarget.of(AllowedModuleKey.STOCK_BALANCES.key(), projection.getId(), Map.of()));
    }

    private MovementRow mapMovement(StockMovementRepository.Material360MovementProjection projection) {
        return new MovementRow(
            projection.getId(),
            projection.getPostedAt(),
            projection.getMovementDate(),
            projection.getMovementType(),
            projection.getStatus(),
            decimal(projection.getQuantity()),
            projection.getUnitOfMeasureCode(),
            projection.getUnitOfMeasureShortName(),
            projection.getWarehouseId(),
            projection.getWarehouseName(),
            projection.getStockBatchId(),
            projection.getBatchNumber(),
            projection.getReferenceType(),
            projection.getReferenceId(),
            LinkTarget.of(AllowedModuleKey.STOCK_MOVEMENTS.key(), projection.getId(), Map.of()));
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    public record InventorySnapshot(
        BigDecimal onHand,
        BigDecimal available,
        BigDecimal reserved,
        BigDecimal quarantine,
        BigDecimal totalValue,
        String currencyCode,
        long warehouseCount,
        LocalDateTime balanceAt
    ) {
    }
}
