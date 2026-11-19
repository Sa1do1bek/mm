package com.enterprise.mm.payload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AssetSettingsUpdateRequest(
    @NotNull UUID centralWarehouseId,
    UUID centralStorageLocationId,
    @Size(max = 30) String inventoryNumberPrefix,
    @Min(1) Long inventoryNumberNextValue,
    @Min(1) Integer inventoryNumberPadding,
    boolean autoNumberAtReceipt,
    boolean requireSerialNumber
) {
}
