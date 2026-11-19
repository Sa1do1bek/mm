package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ManualWarehouseDocumentLineRequest(
    @NotNull UUID contractSpecificationId,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
    @DecimalMin("0") BigDecimal unitCost,
    UUID stockPositionId,
    UUID materialId,
    @Size(max = 1000) List<@NotBlank @Size(max = 120) String> serialNumbers,
    @Size(max = 1000) List<UUID> materialUnitIds
) {}
