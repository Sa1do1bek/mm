package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SupplierAccreditationDocumentActionRequest(
    @NotBlank @Size(min = 3, max = 1000) String reason,
    @NotNull Long expectedVersion
) {
    public SupplierAccreditationDocumentActionRequest(String reason) {
        this(reason, null);
    }
}
