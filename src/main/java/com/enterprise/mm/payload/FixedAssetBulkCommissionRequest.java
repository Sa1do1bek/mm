package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FixedAssetBulkCommissionRequest(
    @NotEmpty List<@Valid Row> rows
) {
    public record Row(
        @NotNull UUID assetId,
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
}
