package com.enterprise.mm.error;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class InsufficientStockException extends BusinessException {
    private final Map<String, Object> details;

    public InsufficientStockException(
        UUID materialId,
        String materialName,
        UUID warehouseId,
        String warehouseName,
        BigDecimal requestedQuantity,
        BigDecimal availableQuantity,
        String unitOfMeasure
    ) {
        this(
            "Insufficient available stock",
            materialId,
            materialName,
            warehouseId,
            warehouseName,
            requestedQuantity,
            availableQuantity,
            unitOfMeasure
        );
    }

    public InsufficientStockException(
        String message,
        UUID materialId,
        String materialName,
        UUID warehouseId,
        String warehouseName,
        BigDecimal requestedQuantity,
        BigDecimal availableQuantity,
        String unitOfMeasure
    ) {
        super(message, "INSUFFICIENT_AVAILABLE_STOCK");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("materialId", materialId);
        values.put("materialName", materialName);
        values.put("warehouseId", warehouseId);
        values.put("warehouseName", warehouseName);
        values.put("requestedQuantity", requestedQuantity == null ? BigDecimal.ZERO : requestedQuantity);
        values.put("availableQuantity", availableQuantity == null ? BigDecimal.ZERO : availableQuantity);
        values.put("unitOfMeasure", unitOfMeasure);
        this.details = Collections.unmodifiableMap(values);
    }

    public Map<String, Object> details() {
        return details;
    }
}
