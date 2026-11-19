package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FixedAssetCommissionRequest(
    @NotNull Long expectedVersion,
    String inventoryNumber,
    @NotNull UUID responsibleEmployeeId,
    @NotNull UUID departmentId,
    String operatingLocation,
    @NotNull LocalDate commissioningDate,
    List<String> evidenceIds,
    String reason
) {
}
