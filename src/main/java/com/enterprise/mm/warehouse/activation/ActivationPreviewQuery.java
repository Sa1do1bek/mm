package com.enterprise.mm.warehouse.activation;

import java.util.UUID;

public record ActivationPreviewQuery(
    String accountCode,
    UUID warehouseId,
    SourcePositionClassification classification,
    int page,
    int size,
    UUID importId,
    java.time.LocalDate cutoverDate
) {
    public ActivationPreviewQuery(String accountCode, UUID warehouseId,
                                  SourcePositionClassification classification, int page, int size) {
        this(accountCode, warehouseId, classification, page, size, null, null);
    }
    public ActivationPreviewQuery {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 50;
        }
    }
}
