package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SupplierUnblockRequestDto(
    @NotBlank @Size(min = 3, max = 2000) String reason,
    UUID purchaseOrderId
) {
}
