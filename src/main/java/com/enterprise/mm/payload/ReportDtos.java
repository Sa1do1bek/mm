package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportDtos {
    private ReportDtos() {
    }

    public enum ReportMetricHealth {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public record ReportMetadataDto(
        Instant generatedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        UUID companyId,
        String scopeLabel,
        String displayCurrency,
        String baseCurrencyCode,
        ReportMetricHealth health,
        List<String> sourceHealthMessages,
        Map<String, String> metricDefinitions,
        Map<String, String> metricScopes
    ) {
    }

    public record QueueContractDto(
        String queueKey,
        List<String> statuses,
        String drilldownUrl
    ) {
    }

    public record DashboardReportDto(
        long materials,
        BigDecimal materialsAmount,
        long suppliers,
        BigDecimal suppliersAmount,
        long openPurchaseRequests,
        BigDecimal openPurchaseRequestsAmount,
        long openPurchaseOrders,
        BigDecimal openPurchaseOrdersAmount,
        long blockedSuppliers,
        BigDecimal blockedSuppliersAmount,
        long nonMovingPositions,
        BigDecimal nonMovingStockValue,
        long overdueDeliveries,
        BigDecimal overdueDeliveriesAmount,
        BigDecimal totalStockValue,
        BigDecimal procurementSavings
    ) {
    }

    public record PlanFactReportDto(
        BigDecimal plannedAmount,
        BigDecimal orderedAmount,
        BigDecimal receivedAmount,
        BigDecimal varianceAmount,
        String currencyCode,
        ReportMetricHealth health,
        String definition
    ) {
        public PlanFactReportDto(
            BigDecimal plannedAmount,
            BigDecimal orderedAmount,
            BigDecimal receivedAmount,
            BigDecimal varianceAmount
        ) {
            this(
                plannedAmount,
                orderedAmount,
                receivedAmount,
                varianceAmount,
                null,
                ReportMetricHealth.READY,
                "Planned, ordered and received purchase value for one currency grouping."
            );
        }
    }

    public record PlanFactCurrencyBucketDto(
        String currencyCode,
        BigDecimal plannedAmount,
        BigDecimal orderedAmount,
        BigDecimal receivedAmount,
        BigDecimal varianceAmount,
        ReportMetricHealth receivedHealth,
        String receivedDefinition,
        List<String> messages
    ) {
        public PlanFactCurrencyBucketDto(
            String currencyCode,
            BigDecimal plannedAmount,
            BigDecimal orderedAmount,
            BigDecimal receivedAmount,
            BigDecimal varianceAmount
        ) {
            this(
                currencyCode,
                plannedAmount,
                orderedAmount,
                receivedAmount,
                varianceAmount,
                ReportMetricHealth.READY,
                "Sum of unique POSTED PurchaseOrder RECEIPT movement cost in the period, divided by the order exchange rate.",
                List.of()
            );
        }
    }

    public record SavingsCurrencyBucketDto(
        String currencyCode,
        BigDecimal amount,
        String definition
    ) {
    }

    public record SupplierRatingReportDto(UUID supplierId, String code, String name, String status, String category, BigDecimal rating) {
    }

    public record StockReportDto(UUID materialId, String materialCode, String materialName, UUID warehouseId, String warehouseCode, String warehouseName, BigDecimal quantity, BigDecimal availableQuantity, BigDecimal reservedQuantity, BigDecimal totalValue) {
    }

    public record MovementReportDto(UUID movementId, UUID materialId, String materialCode, String materialName, UUID warehouseId, String warehouseCode, String warehouseName, String movementType, String status, BigDecimal quantity, BigDecimal totalCost) {
    }

    public record TurnoverReportDto(
        UUID materialId,
        String materialCode,
        String materialName,
        BigDecimal issueQuantity90d,
        BigDecimal availableQuantity,
        BigDecimal turnoverRatio,
        ReportMetricHealth health,
        String definition
    ) {
    }

    public record AbcReportDto(UUID materialId, String materialCode, String materialName, BigDecimal totalValue, BigDecimal cumulativeShare, String category) {
    }

    public record XyzReportDto(UUID materialId, String materialCode, String materialName, BigDecimal averageMonthlyIssue, BigDecimal variationCoefficient, String category) {
    }

    public record AbcXyzMatrixReportDto(UUID materialId, String materialCode, String materialName, BigDecimal totalValue, BigDecimal turnoverRatio, String abcCategory, String xyzCategory, String matrixCategory) {
    }

    public record NonMovingReportDto(UUID id, UUID materialId, String materialCode, String materialName, UUID warehouseId, String warehouseCode, String warehouseName, Integer monthsWithoutMovement, String status, String plannedAction) {
    }

    public record InventoryValuationReportDto(
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        UUID materialId,
        String materialCode,
        String materialName,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        UUID stockBatchId,
        String stockBatchCode,
        String stockBatchName,
        BigDecimal quantity,
        BigDecimal totalValue,
        BigDecimal averageUnitCost,
        BigDecimal impairmentReserve,
        BigDecimal frozenMoney
    ) {
    }

    public record InventoryValuationDashboardDto(
        BigDecimal totalInventoryValue,
        BigDecimal totalImpairmentReserve,
        BigDecimal totalFrozenMoney,
        long materialCount,
        long batchCount,
        List<InventoryValuationReportDto> rows
    ) {
    }

    public record OverdueDeliveryReportDto(UUID purchaseOrderId, String code, UUID supplierId, String supplierCode, String supplierName, LocalDate expectedDeliveryDate, String status, BigDecimal totalAmount) {
    }

    public record ReportsSnapshotDto(
        DashboardReportDto dashboard,
        PlanFactReportDto planFact,
        List<SupplierRatingReportDto> supplierRating,
        List<AbcReportDto> abc,
        List<XyzReportDto> xyz,
        List<NonMovingReportDto> nonMoving,
        List<OverdueDeliveryReportDto> overdueDeliveries,
        ReportMetadataDto metadata,
        List<PlanFactCurrencyBucketDto> planFactByCurrency,
        List<SavingsCurrencyBucketDto> savingsByCurrency,
        List<QueueContractDto> queueContracts
    ) {
    }
}
