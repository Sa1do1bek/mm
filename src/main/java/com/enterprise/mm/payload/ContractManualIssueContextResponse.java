package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContractManualIssueContextResponse(
    UUID contractId,
    String contractCode,
    String contractNumber,
    String contractName,
    UUID supplierId,
    String supplierName,
    String currencyCode,
    LocalDate startDate,
    LocalDate endDate,
    UUID warehouseId,
    String warehouseName,
    List<ContractManualIssueLineResponse> lines
) {
    public record ContractManualIssueLineResponse(
        UUID specificationId,
        UUID materialId,
        String materialCode,
        String materialName,
        String unit,
        BigDecimal contractQuantityLimit,
        BigDecimal contractPrice,
        BigDecimal totalContractAmount,
        String materialType,
        String serialTrackingPolicy,
        WarehouseStockBalanceSummary availableStock,
        List<AvailableStockPositionDto> availablePositions,
        UUID materialGroupId,
        String materialGroupCode,
        String materialGroupName,
        UUID unitOfMeasureId,
        BigDecimal remainingReceiptQuantity,
        String contractQuantityPerStockQuantity,
        WarehouseReceiptProfileDtos.Selection receiptSelection
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unit); }

        // Preserve callers that construct the existing response shape.
        public ContractManualIssueLineResponse(UUID specificationId, UUID materialId, String materialCode,
            String materialName, String unit, BigDecimal contractQuantityLimit, BigDecimal contractPrice,
            BigDecimal totalContractAmount, String materialType, String serialTrackingPolicy,
            WarehouseStockBalanceSummary availableStock, List<AvailableStockPositionDto> availablePositions,
            UUID materialGroupId, String materialGroupCode, String materialGroupName, UUID unitOfMeasureId,
            BigDecimal remainingReceiptQuantity, String contractQuantityPerStockQuantity) {
            this(specificationId, materialId, materialCode, materialName, unit, contractQuantityLimit, contractPrice,
                totalContractAmount, materialType, serialTrackingPolicy, availableStock, availablePositions,
                materialGroupId, materialGroupCode, materialGroupName, unitOfMeasureId, remainingReceiptQuantity,
                contractQuantityPerStockQuantity, null);
        }

        public ContractManualIssueLineResponse withStock(WarehouseStockBalanceSummary stock) {
            return new ContractManualIssueLineResponse(specificationId, materialId, materialCode, materialName, unit,
                contractQuantityLimit, contractPrice, totalContractAmount, materialType, serialTrackingPolicy,
                stock, availablePositions, materialGroupId, materialGroupCode, materialGroupName, unitOfMeasureId,
                remainingReceiptQuantity, contractQuantityPerStockQuantity, receiptSelection);
        }

        public ContractManualIssueLineResponse withReceipt(BigDecimal remaining, UUID groupId, String groupCode,
            String groupName, UUID uomId, WarehouseReceiptProfileDtos.Selection selection) {
            return new ContractManualIssueLineResponse(specificationId, materialId, materialCode, materialName, unit,
                contractQuantityLimit, contractPrice, totalContractAmount, materialType, serialTrackingPolicy,
                availableStock, availablePositions, groupId, groupCode, groupName, uomId, remaining,
                contractQuantityPerStockQuantity, selection);
        }

        public ContractManualIssueLineResponse(UUID specificationId, UUID materialId, String materialCode,
            String materialName, String unit, BigDecimal contractQuantityLimit, BigDecimal contractPrice,
            BigDecimal totalContractAmount, String materialType, WarehouseStockBalanceSummary availableStock,
            List<AvailableStockPositionDto> availablePositions, UUID materialGroupId, String materialGroupCode,
            String materialGroupName, UUID unitOfMeasureId, BigDecimal remainingReceiptQuantity) {
            this(specificationId, materialId, materialCode, materialName, unit, contractQuantityLimit, contractPrice,
                totalContractAmount, materialType, null, availableStock, availablePositions, materialGroupId,
                materialGroupCode, materialGroupName, unitOfMeasureId, remainingReceiptQuantity, null);
        }

        public ContractManualIssueLineResponse(UUID specificationId, UUID materialId, String materialCode,
            String materialName, String unit, BigDecimal contractQuantityLimit, BigDecimal contractPrice,
            BigDecimal totalContractAmount, String materialType, String serialTrackingPolicy,
            WarehouseStockBalanceSummary availableStock, List<AvailableStockPositionDto> availablePositions,
            UUID materialGroupId, String materialGroupCode, String materialGroupName, UUID unitOfMeasureId,
            BigDecimal remainingReceiptQuantity) {
            this(specificationId, materialId, materialCode, materialName, unit, contractQuantityLimit, contractPrice,
                totalContractAmount, materialType, serialTrackingPolicy, availableStock, availablePositions,
                materialGroupId, materialGroupCode, materialGroupName, unitOfMeasureId, remainingReceiptQuantity, null);
        }

        public ContractManualIssueLineResponse(UUID specificationId, UUID materialId, String materialCode,
            String materialName, String unit, BigDecimal contractQuantityLimit, BigDecimal contractPrice,
            BigDecimal totalContractAmount, String materialType, WarehouseStockBalanceSummary availableStock,
            List<AvailableStockPositionDto> availablePositions) {
            this(specificationId, materialId, materialCode, materialName, unit, contractQuantityLimit, contractPrice,
                totalContractAmount, materialType, null, availableStock, availablePositions, null, null, null, null, null, null);
        }

        public ContractManualIssueLineResponse(UUID specificationId, UUID materialId, String materialCode,
            String materialName, String unit, BigDecimal contractQuantityLimit, BigDecimal contractPrice,
            BigDecimal totalContractAmount, String materialType, String serialTrackingPolicy,
            WarehouseStockBalanceSummary availableStock, List<AvailableStockPositionDto> availablePositions) {
            this(specificationId, materialId, materialCode, materialName, unit, contractQuantityLimit, contractPrice,
                totalContractAmount, materialType, serialTrackingPolicy, availableStock, availablePositions,
                null, null, null, null, null, null);
        }
    }

    public record WarehouseStockBalanceSummary(
        BigDecimal availableQuantity,
        BigDecimal onHandQuantity,
        BigDecimal reservedQuantity,
        boolean hasStock,
        UUID defaultStockPositionId
    ) {}

    public record AvailableStockPositionDto(
        UUID stockPositionId,
        UUID stockBatchId,
        String batchNumber,
        UUID storageLocationId,
        String storageLocationName,
        UUID storageBinId,
        String storageBinName,
        BigDecimal availableQuantity,
        LocalDate expirationDate
    ) {}
}
