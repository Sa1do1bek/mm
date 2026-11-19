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
public class IssueRequestIssueRequest {
    @NotNull
    private Long expectedVersion;

    @NotNull
    private UUID warehouseId;

    private UUID storageLocationId;

    private LocalDate issuedDate;

    @Valid
    @NotEmpty
    private List<@NotNull Line> lines;

    @Getter
    @Setter
    public static class Line {
        @NotNull
        private UUID issueRequestLineId;
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal issuedQuantity;
        private UUID storageBinId;
        private UUID stockBatchId;
        private UUID storageLocationId;
        private List<UUID> materialUnitIds;
    }
}
