package com.enterprise.mm.service;

import java.math.BigDecimal;
import java.util.UUID;

public record StockGroup(
    UUID materialId,
    String materialName,
    UUID warehouseId,
    String warehouseName,
    BigDecimal quantity
) {
}
