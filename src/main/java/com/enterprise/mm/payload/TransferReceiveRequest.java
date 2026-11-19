package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferReceiveRequest {
    @NotNull
    private LocalDate receivedDate;
    @NotNull
    private Long expectedVersion;
    @io.swagger.v3.oas.annotations.media.Schema(description = "Omitted/null means PARTIAL for existing clients. The unified receipt table submits FULL_SELECTED_ROWS.", defaultValue = "PARTIAL")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private TransferReceiptDtos.ReceiptMode receiptMode;
    @Valid
    @jakarta.validation.constraints.NotEmpty
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String rowKey;
        private UUID transferLineId;
        private UUID shipmentAllocationId;
        private String conditionEvidence;
        private List<UUID> attachmentIds;
        private BigDecimal receivedQuantity;
        private BigDecimal damagedQuantity;
        private String shortageReason;
        private UUID storageLocationId;
        private UUID storageBinId;
        private List<UUID> materialUnitIds;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<UUID> damagedMaterialUnitIds;
    }
}
