package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferDispatchRequest {
    @NotNull
    private LocalDate dispatchedDate;
    @NotNull
    private Long expectedVersion;
    @Valid
    @jakarta.validation.constraints.NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        @NotNull
        private UUID transferLineId;
        @NotNull
        @jakarta.validation.constraints.Digits(integer = 15, fraction = 7)
        @DecimalMin(value = "0", inclusive = false)
        @com.fasterxml.jackson.annotation.JsonAlias("quantity")
        private BigDecimal dispatchedQuantity;
        // Omit new null fields to preserve the serialized hash of legacy idempotent commands.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.enterprise.mm.enums.PickingMethod pickingMethod;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private com.enterprise.mm.enums.StockAllocationMode allocationMode;
        @Valid
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<Allocation> allocations;
        private UUID stockBatchId;
        private UUID storageLocationId;
        private UUID storageBinId;
        private List<UUID> materialUnitIds;
    }

    @Getter
    @Setter
    public static class Allocation {
        @NotNull
        private UUID stockBalanceId;
        @NotNull
        @jakarta.validation.constraints.Digits(integer = 15, fraction = 7)
        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal quantity;
        private List<UUID> materialUnitIds;
    }
}
