package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueRequestReserveRequest {
    private UUID warehouseId;

    @Valid
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        private UUID issueRequestLineId;
        private BigDecimal reservedQuantity;
        private UUID storageBinId;
        private UUID stockBatchId;
        private UUID storageLocationId;
        private List<UUID> materialUnitIds;
    }
}
