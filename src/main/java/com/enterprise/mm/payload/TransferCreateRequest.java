package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferCreateRequest {
    private String code;
    private String name;
    private String description;
    @NotNull
    private UUID sourceWarehouseId;
    private UUID sourceLocationId;
    @NotNull
    private UUID destinationWarehouseId;
    private UUID destinationLocationId;
    private LocalDate plannedDate;
    @Valid
    @NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        @NotNull
        private UUID materialId;
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal plannedQuantity;
        private UUID stockBatchId;
        private UUID storageLocationId;
    }
}
