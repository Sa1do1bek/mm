package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssetVerificationScanRequest(
    @NotNull Long expectedVersion,
    UUID fixedAssetId,
    String inventoryNumber,
    String observedCustodyKind,
    UUID observedEmployeeId,
    UUID observedDepartmentId,
    UUID observedWarehouseId
) {
}
