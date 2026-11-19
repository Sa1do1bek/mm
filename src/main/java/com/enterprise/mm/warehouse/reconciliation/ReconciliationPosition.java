package com.enterprise.mm.warehouse.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationPosition(
    UUID materialId,
    String materialCode,
    String materialName,
    String uom,
    UUID warehouseId,
    String warehouseCode,
    String warehouseName,
    String accountCode,
    String accountName,
    BigDecimal sourceQuantity,
    BigDecimal sourceAmount,
    BigDecimal nativePhysicalQuantity,
    BigDecimal nativePhysicalAmount,
    BigDecimal provenAccountQuantity,
    BigDecimal provenAccountAmount,
    BigDecimal quantityDelta,
    BigDecimal amountDelta,
    ReconciliationStatus status
) {}
