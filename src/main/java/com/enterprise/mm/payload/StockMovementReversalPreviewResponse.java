package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockMovementReversalPreviewResponse(
    UUID movementId,
    MovementType movementType,
    BigDecimal quantity,
    BigDecimal unitCost,
    BigDecimal totalCost,
    String quantityImpact,
    String valueImpact,
    BigDecimal balanceAfterReversal,
    List<LinkedDocument> linkedDocuments,
    String period,
    boolean periodClosed,
    List<String> warnings
) {
    public record LinkedDocument(String type, UUID id, String code) {
    }
}
