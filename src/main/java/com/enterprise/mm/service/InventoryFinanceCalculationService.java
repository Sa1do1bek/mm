package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.repository.InventoryFxDifferenceRepository;
import com.enterprise.mm.repository.LandedCostAllocationLineRepository;
import com.enterprise.mm.repository.LandedCostAllocationRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockValuationDocumentRepository;
import com.enterprise.mm.repository.StockValuationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryFinanceCalculationService {
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockValuationRepository stockValuationRepository;
    private final StockValuationDocumentRepository stockValuationDocumentRepository;
    private final LandedCostAllocationRepository landedCostAllocationRepository;
    private final LandedCostAllocationLineRepository landedCostAllocationLineRepository;
    private final InventoryFxDifferenceRepository fxDifferenceRepository;
    private final com.enterprise.mm.service.transfer.TransferTransitLedgerService transferTransitLedger;

    public BigDecimal inventoryValue(UUID warehouseId) {
        return physicalInventoryValue(warehouseId).add(transitInventoryValue(warehouseId));
    }
    public BigDecimal transitInventoryValue(UUID warehouseId) {
        return transferTransitLedger.inventoryValue(warehouseId);
    }
    public BigDecimal physicalInventoryValue(UUID warehouseId) {
        return stockBalanceRepository.sumTotalValue(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId
        );
    }
    public long postedMovementCount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return stockMovementRepository.countPostedInPeriod(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            periodStart.atStartOfDay(),
            periodEnd.atTime(LocalTime.MAX)
        );
    }
    public long openMovementCount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return stockMovementRepository.countOpenInPeriod(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            periodStart,
            periodEnd
        );
    }
    public long missingCostLayerBalanceCount(UUID warehouseId) {
        return stockBalanceRepository.countPositiveBalancesWithoutActiveCostLayer(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId
        );
    }
    public long zeroCostInboundCount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return stockMovementRepository.countZeroCostInboundPostedInPeriod(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            java.util.List.of(MovementType.RECEIPT, MovementType.RETURN, MovementType.ADJUSTMENT),
            periodStart.atStartOfDay(),
            periodEnd.atTime(LocalTime.MAX)
        );
    }
    public long openValuationDocumentCount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return stockValuationDocumentRepository.countOpenInPeriod(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            periodStart,
            periodEnd
        );
    }
    public long openLandedCostAllocationLineCount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return landedCostAllocationLineRepository.countOpenAllocationLinesInPeriod(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            periodStart,
            periodEnd
        );
    }
    public long valuationCount(UUID warehouseId) {
        return stockValuationRepository.countActive(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId
        );
    }
    public BigDecimal postedLandedCostAmount(LocalDate periodStart, LocalDate periodEnd) {
        return landedCostAllocationRepository.sumPostedBaseAmount(TenantContext.currentTenant(), CompanyContext.currentCompany(), periodStart, periodEnd);
    }
    public BigDecimal postedFxDifferenceAmount(UUID warehouseId, LocalDate periodStart, LocalDate periodEnd) {
        return fxDifferenceRepository.sumPostedDifference(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId == null,
            warehouseId,
            periodStart,
            periodEnd
        );
    }
}

