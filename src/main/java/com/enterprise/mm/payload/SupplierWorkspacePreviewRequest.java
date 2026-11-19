package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SupplierWorkspacePreviewRequest(
    UUID supplierId,
    @Size(max = 300) String legalName,
    @Size(max = 180) String shortName,
    @Size(max = 500) String taxId,
    @Size(max = 80) String registrationNumber,
    @Size(max = 500) String bankAccount,
    @Size(max = 20) String relationshipType,
    @Size(max = 40) String supplierType,
    List<String> requestedAccreditationTypes
) {
}
