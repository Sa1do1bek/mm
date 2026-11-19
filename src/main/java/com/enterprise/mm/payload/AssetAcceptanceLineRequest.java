package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AssetAcceptanceLineRequest(
    UUID purchaseOrderItemId,
    UUID materialId,
    String materialTypeSnapshot,
    String unit,
    BigDecimal deliveredQuantity,
    List<AssetAcceptanceUnitRequest> units
) {
}
