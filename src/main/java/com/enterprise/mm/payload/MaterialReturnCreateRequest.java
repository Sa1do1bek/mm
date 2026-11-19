package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialReturnCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReturnCreateRequest {
    private UUID issueRequestId;
    @NotNull
    private UUID warehouseId;
    private String reason;
    private String originType = "NATIVE_ISSUE";
    private String historicalDocumentNumber;
    private java.time.LocalDate historicalDocumentDate;
    private String historicalSourceSystem;
    private String ownerId;

    @Valid
    @NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        private UUID issueRequestLineId;
        private UUID materialId;
        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal returnQuantity;
        private BigDecimal issuedQuantity;
        private BigDecimal previouslyReturnedQuantity;
        private BigDecimal originalUnitCost;
        private String serialNumber;
        private String ownerId;
        private UUID stockBatchId;
        private UUID storageLocationId;
        @NotNull
        private MaterialReturnCondition condition;
        private String comment;
    }
}
