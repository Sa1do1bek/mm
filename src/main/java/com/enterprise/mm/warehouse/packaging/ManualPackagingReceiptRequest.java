package com.enterprise.mm.warehouse.packaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;

/** Zero-based line index in the approved manual receipt snapshot. */
public record ManualPackagingReceiptRequest(@NotNull Integer contentLineIndex, @NotNull UUID packagingMaterialId,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
    @NotBlank String ownership, String accountCode,
    @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal unitCost, LocalDate dueDate) {}
