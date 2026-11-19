package com.enterprise.mm.warehouse.print;

import java.math.BigDecimal;
import java.util.UUID;

public record WarehousePrintFormLine(
    int lineIndex,
    UUID materialId,
    String materialCode,
    String materialName,
    String unitOfMeasure,
    String accountCode,
    String correspAccountCode,
    BigDecimal requestedQuantity,
    BigDecimal releasedOrAcceptedQuantity,
    BigDecimal unitPrice,
    BigDecimal totalAmount,
    BigDecimal vatAmount,
    String notes,
    String barcode,
    String qrCode,
    String rfidTag
) {
    public WarehousePrintFormLine(
        int lineIndex,
        UUID materialId,
        String materialCode,
        String materialName,
        String unitOfMeasure,
        String accountCode,
        String correspAccountCode,
        BigDecimal requestedQuantity,
        BigDecimal releasedOrAcceptedQuantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount
    ) {
        this(lineIndex, materialId, materialCode, materialName, unitOfMeasure, accountCode, correspAccountCode,
             requestedQuantity, releasedOrAcceptedQuantity, unitPrice, totalAmount, BigDecimal.ZERO, null, null, null, null);
    }
}
