package com.enterprise.mm.payload;

import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.enums.StockAllocationMode;
import com.enterprise.mm.payload.WarehouseStockFactRequest.Allocation;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The warehouse is resolved from the authenticated actor, never selected by the client. */
public record FixedAssetStockFactRequest(
    @NotNull @PastOrPresent LocalDate movementDate,
    @Size(max = 240) String source,
    @Size(max = 120) String reference,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines
) {
    public record Line(
        @NotNull UUID materialId,
        @NotNull @Digits(integer = 15, fraction = 0) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
        @DecimalMin("0") BigDecimal unitCost,
        PickingMethod pickingMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL) StockAllocationMode allocationMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 1000)
        List<@NotNull @Valid Allocation> allocations
    ) {
        public Line {
            // 2, 2.0 and 2.00 represent the same whole quantity and the same retry payload.
            if (quantity != null) quantity = quantity.stripTrailingZeros();
        }

        public Line(UUID materialId, BigDecimal quantity, BigDecimal unitCost, PickingMethod pickingMethod) {
            this(materialId, quantity, unitCost, pickingMethod, null, null);
        }
    }

    public WarehouseStockFactRequest inWarehouse(UUID warehouseId, boolean receipt) {
        return new WarehouseStockFactRequest(warehouseId, movementDate, source, reference,
            lines.stream().map(line -> new WarehouseStockFactRequest.Line(line.materialId(), line.quantity(),
                line.unitCost(), null, null, null,
                receipt ? null : line.pickingMethod() == null ? PickingMethod.FIFO : line.pickingMethod(),
                receipt ? null : line.allocationMode() == null ? StockAllocationMode.AUTO : line.allocationMode(),
                receipt ? null : line.allocations())).toList());
    }
}
