package com.enterprise.mm.warehouse.packaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A packaging line belongs to one accepted content line in the same receipt. */
public record PackagingReceiptRequest(
    UUID contentPurchaseOrderItemId,
    UUID packagingMaterialId,
    BigDecimal quantity,
    String ownership,
    String accountCode,
    BigDecimal unitCost,
    LocalDate dueDate
) {
    public PackagingReceiptRequest(UUID contentPurchaseOrderItemId, UUID packagingMaterialId,
        BigDecimal quantity, String ownership, String accountCode, BigDecimal unitCost) {
        this(contentPurchaseOrderItemId, packagingMaterialId, quantity, ownership, accountCode, unitCost, null);
    }
}
