package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.StockAllocationMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAlias;

/** A completed physical receipt or issue, optionally recorded with supporting evidence. */
public record WarehouseStockFactRequest(
    @NotNull UUID warehouseId,
    @NotNull @PastOrPresent LocalDate movementDate,
    @Size(max = 240) String source,
    @Size(max = 120) String reference,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines
) {
    public record Line(
        @NotNull UUID materialId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
        @DecimalMin("0") BigDecimal unitCost,
        UUID stockPositionId,
        @Size(max = 1000) List<@NotBlank @Size(max = 120) String> serialNumbers,
        @Size(max = 1000) List<@NotNull UUID> materialUnitIds,
        @JsonInclude(JsonInclude.Include.NON_NULL) PickingMethod pickingMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL) StockAllocationMode allocationMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 1000) List<@NotNull @Valid Allocation> allocations,
        @JsonAlias("accountId") UUID stockAccountId,
        UUID recommendationJobId,
        UUID accountDraftId,
        @Size(max = 500) String purpose
    ) {
        public Line(UUID materialId, BigDecimal quantity, BigDecimal unitCost, UUID stockPositionId,
            List<String> serialNumbers, List<UUID> materialUnitIds, PickingMethod pickingMethod,
            StockAllocationMode allocationMode, List<Allocation> allocations) {
            this(materialId, quantity, unitCost, stockPositionId, serialNumbers, materialUnitIds,
                pickingMethod, allocationMode, allocations, null, null, null, null);
        }

        public Line(UUID materialId, BigDecimal quantity, BigDecimal unitCost, UUID stockPositionId,
            List<String> serialNumbers, List<UUID> materialUnitIds) {
            this(materialId, quantity, unitCost, stockPositionId, serialNumbers, materialUnitIds,
                null, null, null, null, null, null, null);
        }

        public Line(UUID materialId, BigDecimal quantity, BigDecimal unitCost, UUID stockPositionId,
            List<String> serialNumbers, List<UUID> materialUnitIds, PickingMethod pickingMethod,
            StockAllocationMode allocationMode, List<Allocation> allocations, UUID stockAccountId,
            UUID recommendationJobId) {
            this(materialId, quantity, unitCost, stockPositionId, serialNumbers, materialUnitIds,
                pickingMethod, allocationMode, allocations, stockAccountId, recommendationJobId, null, null);
        }
    }
    public record Allocation(
        @NotNull UUID stockBalanceId,
        @NotNull @Digits(integer = 15, fraction = 7) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
        @Size(max = 1000) List<@NotNull UUID> materialUnitIds
    ) {}
}
