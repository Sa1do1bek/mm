package com.enterprise.mm.warehouse.reports;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record WarehouseOverview(LocalDate from, LocalDate to, String currencyCode,
    List<String> warnings, List<WarehouseLine> warehouses) {

    public record UnitTotal(String uom, String unitOfMeasureShortName,
        String unitOfMeasureShortNameRu, String unitOfMeasureShortNameUz,
        String openingQuantity, String receiptQuantity, String issueQuantity, String closingQuantity) {}

    public record WarehouseUnitTotal(UUID warehouseId, String warehouseCode, String warehouseName,
        String uom, String unitOfMeasureShortName, String unitOfMeasureShortNameRu,
        String unitOfMeasureShortNameUz, String openingQuantity, String openingAmount,
        String receiptQuantity, String receiptAmount, String issueQuantity, String issueAmount,
        String closingQuantity, String closingAmount) {}

    public record Approval(UUID id, int revision, OffsetDateTime approvedAt, String approvedBy) {}

    public record WarehouseLine(UUID warehouseId, String warehouseCode, String warehouseName,
        String openingAmount, String receiptAmount, String issueAmount, String closingAmount,
        List<UnitTotal> quantities, Approval approval) {}
}
